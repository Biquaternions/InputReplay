package me.biquaternions.inputreplay.client.io;

/**
 * Record type identifiers and stream assignment. The byte-level layout of each body is documented in
 * {@code tools/irr_format.md} and must stay in sync with {@code tools/irr_dump.py}.
 */
public final class Records {
    private Records() {}

    public enum Stream {
        INPUTS("inputs.bin.gz"),
        PACKETS_IN("packets_in.bin.gz"),
        PACKETS_OUT("packets_out.bin.gz"),
        SNAPSHOTS("snapshots.bin.gz"),
        EVENTS("events.bin.gz");

        public final String fileName;

        Stream(String fileName) {
            this.fileName = fileName;
        }
    }

    // inputs.bin
    public static final int KEY = 1;
    public static final int TEXT = 2;
    public static final int CHAR = 3;
    public static final int MOUSE_BUTTON = 4;
    public static final int SCROLL = 5;
    public static final int CURSOR = 6;
    public static final int TURN = 7;
    public static final int TICK_MARK = 8;
    public static final int KEY_STATE = 9;

    // packets_in.bin / packets_out.bin
    public static final int PACKET_IN = 20;
    public static final int PACKET_APPLY = 21;
    public static final int PACKET_OUT = 22;

    // snapshots.bin
    public static final int SNAPSHOT = 30;

    // events.bin
    public static final int EVENT = 40;

    /** Payload storage modes for packet records. */
    public static final int PAYLOAD_NONE = 0;
    public static final int PAYLOAD_FULL = 1;

    /**
     * One framed record. The header fields are captured on the producing thread; the writer thread
     * frames them (computing the per-stream time delta) and appends to the stream.
     */
    public record Rec(Stream stream, int type, long seq, long tNanos, int tick, long frame, byte[] body) {
        public int approxSize() {
            return body.length + 24;
        }
    }
}
