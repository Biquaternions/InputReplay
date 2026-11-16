package me.biquaternions.inputreplay.client.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.biquaternions.inputreplay.client.Config;
import me.biquaternions.inputreplay.client.capture.InputCapture;
import me.biquaternions.inputreplay.client.io.RecordingReader;
import me.biquaternions.inputreplay.client.io.RecordingReader.InputRec;
import me.biquaternions.inputreplay.client.io.Records;
import me.biquaternions.inputreplay.client.mixin.client.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import me.biquaternions.inputreplay.client.session.BindingResolver;
import me.biquaternions.inputreplay.client.session.Session;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the player from a recording. Per client tick N (anchored), before vanilla {@code tick()}:
 * the recorded TURNs tagged N are applied with {@code LocalPlayer.turn}, then the recorded key /
 * mouse / text events tagged N are injected. Hardware input is blocked from tick 0 until the replay
 * ends; the abort key (default F10) stops it. At the recorded end tick the client disconnects.
 * The run is recorded as well (the session this replayer is attached to).
 */
public final class Replayer {
    private static final Logger LOG = LoggerFactory.getLogger("InputReplay/Replayer");
    private static volatile Replayer active;

    private final Session session;
    private final RecordingReader rec;
    private final InputInjector injector;
    private final DriftMonitor drift = new DriftMonitor(Config.get().driftThreshold);
    private final List<String> bindingDiff;
    private final PacketSync packetSync;
    private boolean started;
    private boolean finished;
    private boolean paused;
    private boolean pendingDisconnect;
    private int nullPlayerTicks;
    private int currentTick = -1;
    private boolean savedPauseOnLostFocus;

    static {
        InputGate.onAbort = () -> {
            Replayer r = active;
            if (r != null) r.abort("aborted");
        };
    }

    private Replayer(Session session, RecordingReader rec, boolean forcePhysical, boolean sync) {
        this.session = session;
        this.rec = rec;
        BindingResolver.Snapshot recorded = BindingResolver.parse(rec.optionsText);
        this.bindingDiff = BindingResolver.bindingDifferences(recorded.bindings(), BindingResolver.currentBindings());
        InputInjector.BindingMode mode = forcePhysical || bindingDiff.isEmpty()
                ? InputInjector.BindingMode.PHYSICAL : InputInjector.BindingMode.BY_NAME;
        this.injector = new InputInjector(mode, session);
        PacketSync ps = null;
        if (sync) {
            try {
                ps = new PacketSync(session, rec.loadPlayerPackets());
            } catch (Exception e) {
                LOG.error("Cannot load player packets of {}; replaying without packet sync", rec.path, e);
            }
        }
        this.packetSync = ps;
        session.metadata.addProperty("replaySync", ps != null);

        session.metadata.addProperty("bindingMode", mode == InputInjector.BindingMode.PHYSICAL ? "physical" : "byName");
        session.metadata.addProperty("replayDurationTicks", rec.durationTicks);
        session.writeMetadata();
        JsonObject d = new JsonObject();
        d.addProperty("file", rec.path.getFileName().toString());
        d.addProperty("bindingMode", mode.name());
        d.addProperty("forcedPhysical", forcePhysical);
        d.addProperty("packetSync", ps != null);
        d.addProperty("durationTicks", rec.durationTicks);
        session.event("REPLAY_ARMED", d);
        if (!bindingDiff.isEmpty()) {
            JsonObject b = new JsonObject();
            JsonArray arr = new JsonArray();
            bindingDiff.forEach(arr::add);
            b.add("mappings", arr);
            session.event("BINDING_MISMATCH", b);
        }
        List<String> optDiff = BindingResolver.relevantOptionDifferences(recorded.options(), BindingResolver.currentOptions());
        if (!optDiff.isEmpty()) {
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            optDiff.forEach(arr::add);
            o.add("options", arr);
            session.event("OPTION_MISMATCH", o);
            LOG.warn("Options differ from the recording: {} (replay may diverge)", optDiff);
        }
        LOG.info("Replay armed: {} ({} ticks, bindings {})", rec.path.getFileName(), rec.durationTicks, mode);
    }

    public static void attach(Session session, RecordingReader rec, boolean forcePhysical, boolean sync) {
        if (active != null) active.stop();
        active = new Replayer(session, rec, forcePhysical, sync);
    }

    // ---------------------------------------------------------------- packet sync hooks

    /** Netty thread, for every decoded inbound packet of the session connection. */
    public static void syncOnDecoded(net.minecraft.network.protocol.Packet<?> packet, String className, String phase, String summary) {
        Replayer r = active;
        if (r != null && r.packetSync != null && !r.finished) r.packetSync.onDecoded(packet, className, phase, summary);
    }

    /** True if sync wants this player packet's summary (avoids summarizing everything). */
    public static boolean syncWantsSummary(String className) {
        Replayer r = active;
        return r != null && r.packetSync != null && !r.packetSync.disabled()
                && (me.biquaternions.inputreplay.client.capture.PlayerPackets.isCandidate(className) || className.equals("ClientboundLoginPacket"));
    }

    /** Main thread, ListenerAndPacket.handle HEAD: true if the packet is held for a later tick. */
    public static boolean syncOnHandle(net.minecraft.network.protocol.Packet<?> packet, Runnable handle) {
        Replayer r = active;
        return r != null && r.packetSync != null && r.packetSync.onHandle(packet, handle);
    }

    /** Main thread, before Minecraft.tick() in runTick: true to skip this tick. */
    public static boolean shouldStallTick() {
        Replayer r = active;
        return r != null && r.packetSync != null && !r.finished && r.packetSync.shouldStall();
    }

    public static Replayer active() {
        return active;
    }

    // ---------------------------------------------------------------- hooks (main thread)

    /** START_CLIENT_TICK, after the session clock advanced and the anchor was checked. */
    public static void beforeTick(Minecraft mc, Session s, int rawTick) {
        Replayer r = active;
        if (r != null && r.session == s) r.doBeforeTick(mc, rawTick);
    }

    /** END_CLIENT_TICK, after the snapshot of this tick was taken. */
    public static void afterTick(Minecraft mc, Session s, int rawTick) {
        Replayer r = active;
        if (r != null && r.session == s) r.doAfterTick(mc);
    }

    /** Minecraft.runTick HEAD: outside of any tick, like a pause-menu button press. */
    public static void onFrame(Minecraft mc) {
        Replayer r = active;
        if (r != null && r.pendingDisconnect) {
            r.pendingDisconnect = false;
            LOG.info("Replay finished; disconnecting");
            mc.disconnectFromWorld(Component.literal("InputReplay: replay finished"));
        }
    }

    /** Called by SessionManager before the session is finalized. */
    public static void onSessionEnd(Session s) {
        Replayer r = active;
        if (r == null || r.session != s) return;
        if (!r.finished) {
            s.setEndReason(s.serverDisconnectSeen ? "server_disconnect_during_replay" : "disconnect_during_replay");
        }
        r.writeSummary();
        r.stop();
        active = null;
    }

    // ---------------------------------------------------------------- per tick

    private void doBeforeTick(Minecraft mc, int rawTick) {
        if (finished || paused || !session.clock.anchored()) return;
        int n = session.clock.anchored(rawTick);
        currentTick = n;
        if (packetSync != null) packetSync.releaseDue(n);
        LocalPlayer player = mc.player;
        if (player == null) {
            if (++nullPlayerTicks > Config.get().maxNullPlayerTicks) abort("player_missing");
            return;
        }
        nullPlayerTicks = 0;
        if (!started) start(mc);
        if (n >= rec.durationTicks) {
            finish("replay_complete");
            return;
        }
        List<InputRec> list = rec.inputsByTick.get(n);
        if (list == null) return;
        for (InputRec r : list) {
            if (r.type() == Records.TURN) {
                InputCapture.onTurn(r.dx(), r.dy());
                player.turn(r.dx(), r.dy());
            }
        }
        for (InputRec r : list) {
            if (r.type() == Records.TURN) continue;
            InputGate.injecting = true;
            try {
                injector.inject(r);
            } catch (RuntimeException e) {
                LOG.error("Failed to inject {} at tick {}", r, n, e);
            } finally {
                InputGate.injecting = false;
            }
        }
    }

    private void doAfterTick(Minecraft mc) {
        if (!started || finished || paused || currentTick < 0) return;
        boolean diverged = drift.compare(currentTick, mc.player, rec.snapshots.get(currentTick));
        if (diverged && Config.get().pauseOnDrift) {
            paused = true;
            JsonObject d = new JsonObject();
            d.addProperty("tick", currentTick);
            d.addProperty("dpos", drift.lastDpos);
            session.event("REPLAY_PAUSED_ON_DRIFT", d);
            session.setEndReason("paused_on_drift");
            release(mc);
            LOG.warn("Replay paused at tick {}: position drift {} > {}", currentTick, drift.lastDpos, Config.get().driftThreshold);
        }
    }

    private void start(Minecraft mc) {
        started = true;
        InputGate.replayActive = true;
        savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false; // a pause screen opening on focus loss would break the run
        KeyMapping.releaseAll();
        VirtualKeyboard.clear();
        // Keys that were held while the world was loading in the original run.
        List<String> held = new ArrayList<>();
        if (rec.preAnchorKeyBits != null) {
            JsonElement namesEl = rec.metadata.get("keyMappingNames");
            if (namesEl != null && namesEl.isJsonArray()) {
                JsonArray names = namesEl.getAsJsonArray();
                byte[] bits = rec.preAnchorKeyBits;
                for (int i = 0; i < names.size() && (i >> 3) < bits.length; i++) {
                    if ((bits[i >> 3] & (1 << (i & 7))) == 0) continue;
                    KeyMapping m = KeyMapping.get(names.get(i).getAsString());
                    if (m != null) {
                        m.setDown(true);
                        InputConstants.Key k = ((KeyMappingAccessor) m).inputreplay$getKey();
                        if (k.getType() == InputConstants.Type.KEYBOARD) VirtualKeyboard.set(k.getValue(), true);
                        held.add(m.getName());
                    }
                }
            }
        }
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        held.forEach(arr::add);
        d.add("heldAtStart", arr);
        session.event("REPLAY_STARTED", d);
        LOG.info("Replay started{}", held.isEmpty() ? "" : " with held keys " + held);
    }

    private void finish(String reason) {
        finished = true;
        session.setEndReason(reason);
        session.event("REPLAY_COMPLETE");
        release(Minecraft.getInstance());
        pendingDisconnect = true;
    }

    public void abort(String reason) {
        if (finished) return;
        finished = true;
        JsonObject d = new JsonObject();
        d.addProperty("reason", reason);
        d.addProperty("tick", currentTick);
        session.event("REPLAY_ABORTED", d);
        LOG.warn("Replay aborted ({}) at tick {}", reason, currentTick);
        release(Minecraft.getInstance());
        SessionManager.endSession(reason);
    }

    private void release(Minecraft mc) {
        if (packetSync != null) packetSync.releaseAll();
        if (InputGate.replayActive) {
            InputGate.replayActive = false;
            VirtualKeyboard.clear();
            KeyMapping.releaseAll();
            mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
        }
    }

    private void stop() {
        finished = true;
        release(Minecraft.getInstance());
    }

    private void writeSummary() {
        JsonObject d = new JsonObject();
        d.addProperty("comparedTicks", drift.compared);
        d.addProperty("maxDpos", drift.maxDpos);
        d.addProperty("firstDivergentTick", drift.firstDivergentTick);
        d.addProperty("lastTick", currentTick);
        if (!injector.missingMappings.isEmpty()) {
            JsonArray arr = new JsonArray();
            injector.missingMappings.forEach(arr::add);
            d.add("missingMappings", arr);
        }
        if (packetSync != null) d.add("packetSync", packetSync.summary());
        session.event("REPLAY_SUMMARY", d);
        session.metadata.add("replaySummary", d);
    }

    // ---------------------------------------------------------------- HUD

    public List<String> hudLines() {
        String state = paused ? "PAUSED (drift)" : finished ? "done" : started ? "F10 abort" : "waiting for player";
        String bind = injector.mode() == InputInjector.BindingMode.PHYSICAL ? "physical" : "byName";
        String first = drift.firstDivergentTick < 0 ? "none" : String.valueOf(drift.firstDivergentTick);
        List<String> lines = new ArrayList<>(List.of(
                String.format("REPLAY  tick %d / %d   Δpos %.4f  Δvel %.4f  Δrot %.3f   bindings: %s",
                        Math.max(currentTick, 0), rec.durationTicks, nanToZero(drift.lastDpos), nanToZero(drift.lastDvel),
                        nanToZero(drift.lastDrot), bind),
                String.format("max Δpos %.4f   first divergence: %s   [%s]", drift.maxDpos, first, state)));
        if (packetSync != null) lines.add(packetSync.hudLine());
        return lines;
    }

    private static double nanToZero(double d) {
        return Double.isNaN(d) ? 0 : d;
    }
}
