package me.biquaternions.inputreplay.client.session;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Session time base. All records carry {@code (seq, t, tick, frame)}:
 * <ul>
 *   <li>{@code tick}: raw client tick index since the session started. Inside a tick it is the index
 *       of the running tick; between ticks it is the index of the next tick (the first tick that
 *       will observe an input). Readers subtract {@code metadata.anchorTick} to get anchored ticks
 *       (tick 0 = first tick with the player loaded).</li>
 *   <li>{@code frame}: frames since session start.</li>
 *   <li>{@code t}: {@code System.nanoTime()} relative to session start.</li>
 *   <li>{@code seq}: global sequence number across all streams.</li>
 * </ul>
 * Written on the main thread; read from any thread (Netty stamps are therefore approximate, the
 * apply stamps taken on the main thread are exact).
 */
public final class TickClock {
    private final long startNanos = System.nanoTime();
    private final AtomicLong seq = new AtomicLong();
    private volatile int ticksStarted;
    private volatile boolean inTick;
    private volatile long frame;
    private volatile int anchorTick = Integer.MIN_VALUE;
    private int framesSinceLastTick;

    public long nextSeq() {
        return seq.getAndIncrement();
    }

    public long peekSeq() {
        return seq.get();
    }

    public long t() {
        return System.nanoTime() - startNanos;
    }

    /** Tick stamp for a record created now (see class doc). */
    public int tick() {
        int n = ticksStarted;
        return inTick ? n - 1 : n;
    }

    public long frame() {
        return frame;
    }

    /** Called at the start of each client tick. Returns the index of the tick that is starting. */
    public int startTick() {
        int idx = ticksStarted;
        ticksStarted = idx + 1;
        inTick = true;
        return idx;
    }

    public void endTick() {
        inTick = false;
        framesSinceLastTick = 0;
    }

    public void newFrame() {
        frame = frame + 1;
        framesSinceLastTick++;
    }

    public int framesSinceLastTick() {
        return framesSinceLastTick;
    }

    public boolean inTick() {
        return inTick;
    }

    public boolean anchored() {
        return anchorTick != Integer.MIN_VALUE;
    }

    public int anchorTick() {
        return anchorTick;
    }

    public void setAnchor(int rawTick) {
        anchorTick = rawTick;
    }

    /** Anchored index of a raw tick (tick 0 = player loaded); raw value if not anchored yet. */
    public int anchored(int rawTick) {
        return anchored() ? rawTick - anchorTick : rawTick;
    }
}
