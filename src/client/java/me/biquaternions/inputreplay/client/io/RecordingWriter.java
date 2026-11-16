package me.biquaternions.inputreplay.client.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes one recording. Streams are gzip'd into {@code <name>.irr.partial/} by a single writer
 * thread fed from a byte-bounded queue; {@link #finish} assembles the final {@code <name>.irr} zip.
 * <p>
 * Producers never block: when the pending byte budget is exceeded, records are dropped and the
 * overflow is reported through {@link #takeOverflowNotice()} and the metadata.
 * <p>
 * A crash leaves the {@code .partial} directory behind. Each gzip stream is sync-flushed once per
 * second, so it can be read up to the last flush ({@code irr_dump.py} accepts the directory).
 */
public final class RecordingWriter {
    private static final Logger LOG = LoggerFactory.getLogger("InputReplay/Writer");
    private static final List<String> ZIP_ORDER = List.of("metadata.json", "options.txt",
            "inputs.bin.gz", "packets_in.bin.gz", "packets_out.bin.gz", "snapshots.bin.gz", "events.bin.gz");

    private final Path partialDir;
    private final Path finalFile;
    private final long maxPendingBytes;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicLong pendingBytes = new AtomicLong();
    private final AtomicLong writtenBytes = new AtomicLong();
    private final AtomicLong droppedRecords = new AtomicLong();
    private final AtomicBoolean overflow = new AtomicBoolean();
    private final AtomicBoolean overflowNotice = new AtomicBoolean();
    private final Map<Records.Stream, OutputStream> outs = new EnumMap<>(Records.Stream.class);
    private final Map<Records.Stream, CountingStream> counters = new EnumMap<>(Records.Stream.class);
    private final long[] lastT = new long[Records.Stream.values().length];
    private final Thread thread;
    private volatile boolean finished;

    private record Control(String kind, String name, String text, Consumer<Path> onDone) {}

    public RecordingWriter(Path recordingsDir, String baseName, long maxPendingBytes) throws IOException {
        Files.createDirectories(recordingsDir);
        this.partialDir = recordingsDir.resolve(baseName + ".irr.partial");
        this.finalFile = recordingsDir.resolve(baseName + ".irr");
        this.maxPendingBytes = maxPendingBytes;
        Files.createDirectories(partialDir);
        for (Records.Stream s : Records.Stream.values()) {
            CountingStream cs = new CountingStream(Files.newOutputStream(partialDir.resolve(s.fileName)));
            counters.put(s, cs);
            outs.put(s, new GZIPOutputStream(new BufferedOutputStream(cs, 1 << 16), 1 << 16, true));
        }
        thread = new Thread(this::run, "InputReplay-Writer-" + baseName);
        thread.setDaemon(true);
        thread.start();
    }

    public Path finalFile() {
        return finalFile;
    }

    public Path partialDir() {
        return partialDir;
    }

    /** Never blocks. Returns false if the record was dropped (queue full or writer finished). */
    public boolean submit(Records.Rec rec) {
        if (finished) return false;
        int size = rec.approxSize();
        if (pendingBytes.addAndGet(size) > maxPendingBytes) {
            pendingBytes.addAndGet(-size);
            droppedRecords.incrementAndGet();
            if (!overflow.getAndSet(true)) {
                LOG.warn("Recording queue exceeded {} bytes; dropping records (recording continues)", maxPendingBytes);
            }
            overflowNotice.set(true);
            return false;
        }
        queue.add(rec);
        return true;
    }

    /** Writes (or replaces) a small text file inside the recording, in queue order. */
    public void writeText(String name, String text) {
        if (!finished) queue.add(new Control("text", name, text, null));
    }

    /**
     * Writes the final metadata, assembles the zip and deletes the partial directory. {@code onDone}
     * runs on the writer thread with the final path (or the partial dir if assembly failed).
     */
    public void finish(String finalMetadataJson, Consumer<Path> onDone) {
        if (finished) return;
        queue.add(new Control("text", "metadata.json", finalMetadataJson, null));
        queue.add(new Control("finish", null, null, onDone));
        finished = true;
    }

    /** True once after each burst of dropped records; used to log a QUEUE_OVERFLOW event. */
    public boolean takeOverflowNotice() {
        return overflowNotice.getAndSet(false);
    }

    public boolean overflowed() {
        return overflow.get();
    }

    public long droppedRecords() {
        return droppedRecords.get();
    }

    public long pendingBytes() {
        return pendingBytes.get();
    }

    /** Compressed bytes written to disk so far. */
    public long writtenBytes() {
        return writtenBytes.get();
    }

    private void run() {
        Bin header = new Bin(32);
        long lastFlush = System.nanoTime();
        try {
            while (true) {
                Object item = queue.poll(250, TimeUnit.MILLISECONDS);
                if (item instanceof Records.Rec rec) {
                    pendingBytes.addAndGet(-rec.approxSize());
                    writeRecord(header, rec);
                } else if (item instanceof Control c) {
                    if (c.kind.equals("text")) {
                        writeTextFile(c.name, c.text);
                    } else if (c.kind.equals("finish")) {
                        Path result = doFinish();
                        if (c.onDone != null) c.onDone.accept(result);
                        return;
                    }
                }
                long now = System.nanoTime();
                if (now - lastFlush > 1_000_000_000L) {
                    lastFlush = now;
                    for (OutputStream o : outs.values()) o.flush();
                    updateWritten();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.error("Recording writer failed; partial data left in {}", partialDir, t);
            finished = true;
        }
    }

    private void updateWritten() {
        long sum = 0;
        for (CountingStream c : counters.values()) sum += c.count;
        writtenBytes.set(sum);
    }

    private void writeRecord(Bin header, Records.Rec rec) throws IOException {
        int si = rec.stream().ordinal();
        long dt = rec.tNanos() - lastT[si];
        lastT[si] = rec.tNanos();
        // body prefix: seq, dt, tick, frame
        Bin prefix = new Bin(24);
        prefix.uvarint(rec.seq()).svarint(dt).svarint(rec.tick()).uvarint(rec.frame());
        header.reset();
        header.u8(rec.type()).uvarint(prefix.length() + rec.body().length);
        OutputStream out = outs.get(rec.stream());
        out.write(header.array(), 0, header.length());
        out.write(prefix.array(), 0, prefix.length());
        out.write(rec.body());
    }

    private void writeTextFile(String name, String text) throws IOException {
        Path tmp = partialDir.resolve(name + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, partialDir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path doFinish() {
        try {
            for (OutputStream o : outs.values()) o.close();
            updateWritten();
            Path tmpZip = finalFile.resolveSibling(finalFile.getFileName() + ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpZip), 1 << 16))) {
                for (String name : ZIP_ORDER) {
                    Path p = partialDir.resolve(name);
                    if (!Files.exists(p)) continue;
                    ZipEntry e = new ZipEntry(name);
                    if (name.endsWith(".gz")) {
                        // already compressed: store as-is
                        e.setMethod(ZipEntry.STORED);
                        long size = Files.size(p);
                        e.setSize(size);
                        e.setCompressedSize(size);
                        e.setCrc(crc(p));
                    }
                    zip.putNextEntry(e);
                    Files.copy(p, zip);
                    zip.closeEntry();
                }
            }
            Files.move(tmpZip, finalFile, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(partialDir);
            LOG.info("Recording finalized: {}", finalFile);
            return finalFile;
        } catch (IOException e) {
            LOG.error("Failed to assemble {}; streams left in {}", finalFile, partialDir, e);
            return partialDir;
        }
    }

    private static long crc(Path p) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        volatile long count;

        CountingStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
