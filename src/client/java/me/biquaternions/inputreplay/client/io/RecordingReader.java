package me.biquaternions.inputreplay.client.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads what the replayer needs from a recording ({@code .irr} zip or crashed {@code .irr.partial}
 * directory): metadata, options, the input stream grouped by anchored tick, the per-tick snapshots
 * (drift monitor) and the last pre-anchor key state. Packet streams are not loaded.
 */
public final class RecordingReader {

    /** One decoded input record. {@code fields} is the record body after the common prefix, for re-emission. */
    public record InputRec(int type, long seq, int tick, byte[] fields,
                           int action, int key, int scancode, int modifiers, int button,
                           double x, double y, double dx, double dy,
                           String text, int codepoint, List<String> mappingNames, byte[] keyBits) {}

    /** Snapshot subset used for drift: position, velocity and rotation. */
    public record Snap(double x, double y, double z, double vx, double vy, double vz, float yRot, float xRot) {}

    public final Path path;
    public final JsonObject metadata;
    public final String optionsText;
    public final int anchorTick;
    /** anchored tick → inputs (seq order). Ticks &lt; 0 are pre-anchor. */
    public final TreeMap<Integer, List<InputRec>> inputsByTick = new TreeMap<>();
    /** anchored tick → snapshot. */
    public final TreeMap<Integer, Snap> snapshots = new TreeMap<>();
    /** Key state bits of the last tick before the anchor (keys held while loading), or null. */
    public byte[] preAnchorKeyBits;
    /** Number of ticks to replay: tick 0 .. durationTicks-1. */
    public final int durationTicks;

    // ---------------------------------------------------------------- light metadata access

    /** Metadata + options only (for the recording list). */
    public record Header(Path path, JsonObject metadata, String optionsText, boolean partial) {
        public String str(String key, String def) {
            JsonElement e = metadata.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsString();
        }

        public int integer(String key, int def) {
            JsonElement e = metadata.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsInt();
        }
    }

    public static Header readHeader(Path path) throws IOException {
        try (Source src = Source.open(path)) {
            byte[] meta = src.read("metadata.json");
            byte[] opts = src.read("options.txt");
            JsonObject m = meta == null ? new JsonObject() : JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Header(path, m, opts == null ? "" : new String(opts, StandardCharsets.UTF_8), Files.isDirectory(path));
        }
    }

    // ---------------------------------------------------------------- full load

    public RecordingReader(Path path) throws IOException {
        this.path = path;
        try (Source src = Source.open(path)) {
            byte[] meta = src.read("metadata.json");
            if (meta == null) throw new IOException("No metadata.json in " + path);
            metadata = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] opts = src.read("options.txt");
            optionsText = opts == null ? "" : new String(opts, StandardCharsets.UTF_8);

            Integer anchor = null;
            JsonElement a = metadata.get("anchorTick");
            if (a != null && !a.isJsonNull()) anchor = a.getAsInt();
            if (anchor == null) anchor = anchorFromEvents(gunzip(src.read(Records.Stream.EVENTS.fileName)));
            if (anchor == null) throw new IOException("Recording has no PLAYER_LOADED anchor (player never loaded)");
            anchorTick = anchor;

            parseInputs(gunzip(src.read(Records.Stream.INPUTS.fileName)));
            parseSnapshots(gunzip(src.read(Records.Stream.SNAPSHOTS.fileName)));
        }
        JsonElement d = metadata.get("durationTicks");
        int dur = d == null || d.isJsonNull() ? 0 : d.getAsInt();
        if (dur <= 0) dur = snapshots.isEmpty() ? 0 : snapshots.lastKey() + 1;
        durationTicks = dur;
    }

    /** Iterates framed records: calls back with (type, seq, rawTick, reader positioned at the fields, end). */
    interface RecordVisitor {
        void visit(int type, long seq, int rawTick, BinReader fields, int fieldsStart, int end);
    }

    /** Full header of one framed record, for consumers that need dt and frame too. */
    interface FullVisitor {
        void visit(int type, long seq, long t, int rawTick, long frame, BinReader fields, int fieldsStart, int end);
    }

    static void forEach(byte[] data, RecordVisitor v) {
        forEachFull(data, (type, seq, t, tick, frame, f, start, end) -> v.visit(type, seq, tick, f, start, end));
    }

    static void forEachFull(byte[] data, FullVisitor v) {
        if (data == null) return;
        long[] t = {0};
        BinReader r = new BinReader(data);
        while (r.remaining() > 0) {
            int type;
            int end;
            try {
                type = r.u8();
                int len = (int) r.uvarint();
                end = r.pos() + len;
                if (end > data.length) return; // truncated tail
            } catch (RuntimeException e) {
                return;
            }
            BinReader body = new BinReader(data, r.pos(), end);
            try {
                long seq = body.uvarint();
                t[0] += body.svarint();
                int tick = (int) body.svarint();
                long frame = body.uvarint();
                v.visit(type, seq, t[0], tick, frame, body, body.pos(), end);
            } catch (RuntimeException ignored) {
                // undecodable record: skip it
            }
            r.pos(end);
        }
    }

    static Integer anchorFromEvents(byte[] data) {
        Integer[] out = {null};
        forEach(data, (type, seq, tick, f, start, end) -> {
            if (out[0] != null || type != Records.EVENT) return;
            if (!f.str().equals("PLAYER_LOADED")) return;
            JsonObject d = JsonParser.parseString(f.str()).getAsJsonObject();
            out[0] = d.has("rawTick") ? d.get("rawTick").getAsInt() : tick;
        });
        return out[0];
    }

    private void parseInputs(byte[] data) {
        int[] lastPreTick = {Integer.MIN_VALUE};
        forEach(data, (type, seq, rawTick, f, start, end) -> {
            int tick = rawTick - anchorTick;
            byte[] fields = Arrays.copyOfRange(data, start, end);
            InputRec rec = switch (type) {
                case Records.KEY -> {
                    int action = (int) f.svarint(), key = (int) f.svarint(), sc = (int) f.svarint(), mods = (int) f.svarint();
                    yield new InputRec(type, seq, tick, fields, action, key, sc, mods, 0, 0, 0, 0, 0, null, 0, f.strs(), null);
                }
                case Records.TEXT -> new InputRec(type, seq, tick, fields, 0, 0, 0, 0, 0, 0, 0, 0, 0, f.str(), 0, List.of(), null);
                case Records.CHAR -> new InputRec(type, seq, tick, fields, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, (int) f.svarint(), List.of(), null);
                case Records.MOUSE_BUTTON -> {
                    int action = (int) f.svarint(), button = (int) f.svarint(), mods = (int) f.svarint();
                    double x = f.f64(), y = f.f64();
                    yield new InputRec(type, seq, tick, fields, action, 0, 0, mods, button, x, y, 0, 0, null, 0, f.strs(), null);
                }
                case Records.SCROLL -> new InputRec(type, seq, tick, fields, 0, 0, 0, 0, 0, 0, 0, f.f64(), f.f64(), null, 0, List.of(), null);
                case Records.CURSOR -> new InputRec(type, seq, tick, fields, 0, 0, 0, 0, 0, f.f64(), f.f64(), f.f64(), f.f64(), null, 0, List.of(), null);
                case Records.TURN -> new InputRec(type, seq, tick, fields, 0, 0, 0, 0, 0, 0, 0, f.f64(), f.f64(), null, 0, List.of(), null);
                case Records.KEY_STATE -> {
                    byte[] bits = f.bytes();
                    if (tick < 0 && tick >= lastPreTick[0]) {
                        lastPreTick[0] = tick;
                        preAnchorKeyBits = bits;
                    }
                    yield null;
                }
                default -> null; // TICK_MARK and unknown types are not replayed
            };
            if (rec != null) inputsByTick.computeIfAbsent(tick, k -> new ArrayList<>()).add(rec);
        });
        for (List<InputRec> list : inputsByTick.values()) list.sort((p, q) -> Long.compare(p.seq(), q.seq()));
    }

    private void parseSnapshots(byte[] data) {
        forEach(data, (type, seq, rawTick, f, start, end) -> {
            if (type != Records.SNAPSHOT) return;
            double x = f.f64(), y = f.f64(), z = f.f64();
            float yRot = f.f32(), xRot = f.f32();
            f.f32(); // yHeadRot
            double vx = f.f64(), vy = f.f64(), vz = f.f64();
            snapshots.put(rawTick - anchorTick, new Snap(x, y, z, vx, vy, vz, yRot, xRot));
        });
    }

    /** Decompresses a gzip stream, keeping whatever decodes if it is truncated. */
    static byte[] gunzip(byte[] data) throws IOException {
        if (data == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (EOFException e) {
            // truncated (crash): use what was decoded
        }
        return out.toByteArray();
    }

    /** Zip file or partial directory. */
    interface Source extends AutoCloseable {
        byte[] read(String name) throws IOException;

        @Override
        void close() throws IOException;

        static Source open(Path path) throws IOException {
            if (Files.isDirectory(path)) {
                return new Source() {
                    public byte[] read(String name) throws IOException {
                        Path p = path.resolve(name);
                        return Files.exists(p) ? Files.readAllBytes(p) : null;
                    }

                    public void close() {}
                };
            }
            ZipFile zip = new ZipFile(path.toFile());
            return new Source() {
                public byte[] read(String name) throws IOException {
                    ZipEntry e = zip.getEntry(name);
                    if (e == null) return null;
                    try (InputStream in = zip.getInputStream(e)) {
                        return in.readAllBytes();
                    }
                }

                public void close() throws IOException {
                    zip.close();
                }
            };
        }
    }
}
