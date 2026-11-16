package me.biquaternions.inputreplay.client.replay;

import com.google.gson.JsonObject;
import me.biquaternions.inputreplay.client.Config;
import me.biquaternions.inputreplay.client.capture.PlayerPackets;
import me.biquaternions.inputreplay.client.io.RecordingReader.PlayerPacket;
import me.biquaternions.inputreplay.client.session.Session;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Packet-sync replay mode: inbound packets that affect the local player's physics (see
 * {@link PlayerPackets}) are applied at the same anchored tick as in the source recording.
 * <ul>
 *   <li>The k-th player packet of the live run is matched to the k-th of the recording (same class
 *       required, otherwise sync is disabled for the rest of the run).</li>
 *   <li><b>Early</b>: a packet the recording applied before tick N that arrives before tick N-1 has
 *       run is held, and handled at the start of tick N.</li>
 *   <li><b>Late</b>: if tick N is about to start and a packet the recording applied before tick N has
 *       not arrived, the client tick is skipped (frames keep rendering) until it arrives, up to
 *       {@code Config.syncTimeoutMs}; on timeout, sync is disabled for the rest of the run.</li>
 * </ul>
 * Player packets keep their relative order: once one is held, the following ones queue behind it.
 */
public final class PacketSync {
    private static final Logger LOG = LoggerFactory.getLogger("InputReplay/Sync");
    private static final int NONE = 0, RECEIVED = 1, HELD = 2, APPLIED = 3;

    private record Held(int index, Runnable handle, List<Integer> indices) {}

    private final Session session;
    private final List<PlayerPacket> source;
    private final AtomicIntegerArray state;
    private final AtomicInteger liveCount = new AtomicInteger();
    private final Map<Object, Integer> liveIndex = new IdentityHashMap<>();
    private final ArrayDeque<Held> held = new ArrayDeque<>();
    private volatile Integer livePlayerId;
    private volatile String disabledReason;
    private boolean releasing;
    private int nextIdx;

    // statistics (main thread)
    private long stallStartNanos = -1;
    private int stalls;
    private long stallNanos;
    private int heldCount;
    private String waitingFor;

    public PacketSync(Session session, List<PlayerPacket> source) {
        this.session = session;
        this.source = source;
        this.state = new AtomicIntegerArray(source.size());
    }

    public boolean disabled() {
        return disabledReason != null;
    }

    private void disable(String reason) {
        if (disabledReason != null) return;
        disabledReason = reason;
        LOG.warn("Packet sync disabled: {}", reason);
        JsonObject d = new JsonObject();
        d.addProperty("reason", reason);
        session.event("SYNC_DISABLED", d);
    }

    // ------------------------------------------------------------------ Netty thread

    /** From packet decoding: classifies the packet and assigns its index among player packets. */
    public void onDecoded(Packet<?> packet, String className, String phase, String summary) {
        if (disabledReason != null || !"play".equals(phase)) return;
        Integer id = PlayerPackets.loginPlayerId(className, summary);
        if (id != null) {
            livePlayerId = id;
            return;
        }
        if (!PlayerPackets.targetsPlayer(className, summary, livePlayerId)) return;
        int k = liveCount.getAndIncrement();
        if (k >= source.size()) {
            disable("the live run received more player packets than the recording (" + source.size() + ")");
            return;
        }
        if (!source.get(k).className().equals(className)) {
            disable("player packet #" + k + " is " + className + " but the recording has " + source.get(k).className());
            return;
        }
        synchronized (liveIndex) {
            liveIndex.put(packet, k);
        }
        state.set(k, RECEIVED);
    }

    // ------------------------------------------------------------------ main thread

    private List<Integer> indicesOf(Packet<?> packet) {
        List<Integer> out = new ArrayList<>(1);
        synchronized (liveIndex) {
            if (packet instanceof BundlePacket<?> bundle) {
                for (Packet<?> sub : bundle.subPackets()) {
                    Integer k = liveIndex.remove(sub);
                    if (k != null) out.add(k);
                }
            } else {
                Integer k = liveIndex.remove(packet);
                if (k != null) out.add(k);
            }
        }
        return out;
    }

    /**
     * From {@code PacketProcessor.ListenerAndPacket.handle} HEAD. Returns true if the packet is held
     * (the call must be cancelled); {@code handle} re-runs it later.
     */
    public boolean onHandle(Packet<?> packet, Runnable handle) {
        if (releasing) return false;
        List<Integer> idx = indicesOf(packet);
        if (idx.isEmpty()) return false;
        int k = idx.getFirst();
        if (disabledReason == null && (!held.isEmpty() || mustWait(k))) {
            for (int i : idx) state.set(i, HELD);
            held.add(new Held(k, handle, idx));
            heldCount++;
            return true;
        }
        for (int i : idx) state.set(i, APPLIED);
        return false;
    }

    private boolean mustWait(int k) {
        Integer n = source.get(k).applyTick();
        if (n == null || n < 0) return false;
        if (!session.clock.anchored()) return true;
        return n > session.clock.anchored(session.clock.tick());
    }

    /** Start of anchored tick N, before inputs are injected: handles held packets that are due. */
    public void releaseDue(int n) {
        while (!held.isEmpty()) {
            Held h = held.peekFirst();
            Integer due = source.get(h.index()).applyTick();
            if (disabledReason == null && due != null && due > n) break;
            held.pollFirst();
            for (int i : h.indices()) state.set(i, APPLIED);
            releasing = true;
            try {
                h.handle().run();
            } finally {
                releasing = false;
            }
        }
    }

    /** Releases everything (sync disabled, replay ended). */
    public void releaseAll() {
        if (disabledReason == null) disabledReason = "replay ended";
        releaseDue(Integer.MAX_VALUE);
    }

    /**
     * Before {@code Minecraft.tick()} in {@code runTick}: true if the next tick must wait for a
     * player packet that the recording had applied by then.
     */
    public boolean shouldStall() {
        if (disabledReason != null) {
            if (!held.isEmpty()) releaseDue(Integer.MAX_VALUE);
            endStall();
            return false;
        }
        if (!session.clock.anchored()) return false;
        int next = session.clock.anchored(session.clock.tick());
        while (nextIdx < source.size() && state.get(nextIdx) == APPLIED) nextIdx++;
        String missing = null;
        for (int k = nextIdx; k < source.size(); k++) {
            Integer n = source.get(k).applyTick();
            if (n == null || n < 0) continue;
            if (n > next) break;
            int st = state.get(k);
            if (st != APPLIED && st != HELD) {
                missing = source.get(k).className() + " #" + k;
                break;
            }
        }
        if (missing == null) {
            endStall();
            return false;
        }
        long now = System.nanoTime();
        if (stallStartNanos < 0) {
            stallStartNanos = now;
            stalls++;
        }
        waitingFor = missing;
        if (now - stallStartNanos > Config.get().syncTimeoutMs * 1_000_000L) {
            endStall();
            disable("timed out at tick " + next + " waiting for " + missing);
            releaseDue(Integer.MAX_VALUE);
            return false;
        }
        return true;
    }

    private void endStall() {
        if (stallStartNanos >= 0) {
            stallNanos += System.nanoTime() - stallStartNanos;
            stallStartNanos = -1;
        }
        waitingFor = null;
    }

    public String hudLine() {
        if (disabledReason != null) return "sync: OFF (" + disabledReason + ")";
        return String.format("sync: %d/%d player packets, %d held, %d stalls (%.0f ms)%s",
                nextIdx, source.size(), heldCount, stalls, stallNanos / 1e6,
                waitingFor == null ? "" : "  WAITING for " + waitingFor);
    }

    public JsonObject summary() {
        JsonObject d = new JsonObject();
        d.addProperty("sourcePlayerPackets", source.size());
        d.addProperty("livePlayerPackets", liveCount.get());
        d.addProperty("held", heldCount);
        d.addProperty("stalls", stalls);
        d.addProperty("stallMs", stallNanos / 1e6);
        d.addProperty("disabledReason", disabledReason);
        return d;
    }
}
