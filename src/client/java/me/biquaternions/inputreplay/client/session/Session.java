package me.biquaternions.inputreplay.client.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import me.biquaternions.inputreplay.client.io.Bin;
import me.biquaternions.inputreplay.client.io.RecordingWriter;
import me.biquaternions.inputreplay.client.io.Records;
import me.biquaternions.inputreplay.client.io.Records.Stream;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One recording session (a record run, or the recording made alongside a replay run). Owns the
 * writer, the clock and the metadata; producers on any thread call {@link #emit}.
 */
public final class Session {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    public final Mode mode;
    public final TickClock clock = new TickClock();
    public final RecordingWriter writer;
    public final JsonObject metadata;
    /** Key mapping names in the order used by KEY_STATE bitsets (fixed for the session). */
    public final List<String> keyMappingNames;
    public final AtomicLong inPackets = new AtomicLong();
    public final AtomicLong outPackets = new AtomicLong();

    /** The client Netty channel being captured; replaced on server transfer. */
    public volatile io.netty.channel.Channel channel;
    public volatile boolean transferPending;
    public volatile boolean serverDisconnectSeen;
    public volatile boolean localDisconnect;
    private volatile String endReasonOverride;
    private volatile boolean ended;
    public int lastTick = -1;

    public Session(Mode mode, RecordingWriter writer, JsonObject metadata, List<String> keyMappingNames) {
        this.mode = mode;
        this.writer = writer;
        this.metadata = metadata;
        this.keyMappingNames = keyMappingNames;
    }

    /** Creates and submits a record stamped now. Safe from any thread. */
    public boolean emit(Stream stream, int type, Bin body) {
        return emit(stream, type, clock.nextSeq(), clock.t(), clock.tick(), clock.frame(), body);
    }

    public boolean emit(Stream stream, int type, long seq, long t, int tick, long frame, Bin body) {
        if (ended) return false;
        return writer.submit(new Records.Rec(stream, type, seq, t, tick, frame, body.toByteArray()));
    }

    public void event(String name, JsonObject details) {
        Bin b = new Bin(64).str(name).str(details == null ? "{}" : COMPACT.toJson(details));
        emit(Stream.EVENTS, Records.EVENT, b);
    }

    public void event(String name) {
        event(name, null);
    }

    public boolean ended() {
        return ended;
    }

    public void setEndReason(String reason) {
        if (endReasonOverride == null) endReasonOverride = reason;
    }

    public String endReason() {
        if (endReasonOverride != null) return endReasonOverride;
        if (serverDisconnectSeen) return "server_disconnect";
        if (localDisconnect) return "user_disconnect";
        return "connection_lost";
    }

    public void writeMetadata() {
        writer.writeText("metadata.json", GSON.toJson(metadata));
    }

    /** Ends the session: final metadata and zip assembly happen on the writer thread. */
    public void finish(java.util.function.Consumer<java.nio.file.Path> onDone) {
        if (ended) return;
        String reason = endReason();
        JsonObject details = new JsonObject();
        details.addProperty("reason", reason);
        event("SESSION_END", details);
        ended = true;
        metadata.addProperty("endedAt", Instant.now().toString());
        metadata.addProperty("endReason", reason);
        metadata.addProperty("anchorTick", clock.anchored() ? clock.anchorTick() : null);
        metadata.addProperty("durationTicks", clock.anchored() ? Math.max(0, lastTick - clock.anchorTick() + 1) : 0);
        metadata.addProperty("rawTicks", lastTick + 1);
        metadata.addProperty("inPackets", inPackets.get());
        metadata.addProperty("outPackets", outPackets.get());
        metadata.addProperty("overflow", writer.overflowed());
        metadata.addProperty("droppedRecords", writer.droppedRecords());
        writer.finish(GSON.toJson(metadata), onDone);
    }
}
