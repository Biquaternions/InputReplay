package me.biquaternions.inputreplay.client.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.biquaternions.inputreplay.client.Config;
import me.biquaternions.inputreplay.client.capture.InputCapture;
import me.biquaternions.inputreplay.client.capture.PacketCapture;
import me.biquaternions.inputreplay.client.io.Bin;
import me.biquaternions.inputreplay.client.io.RecordingWriter;
import me.biquaternions.inputreplay.client.io.Records;
import me.biquaternions.inputreplay.client.io.Records.Stream;
import me.biquaternions.inputreplay.client.mixin.client.ConnectionAccessor;
import me.biquaternions.inputreplay.client.mixin.client.KeyMappingAccessor;
import me.biquaternions.inputreplay.client.io.RecordingReader;
import me.biquaternions.inputreplay.client.replay.Replayer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Session lifecycle: IDLE → ARMED(mode) → RECORDING | REPLAYING → FINALIZING → IDLE.
 * <p>
 * A mode is armed before connecting (prompt screen, command or system properties). The session is
 * created when {@code ConnectScreen.startConnecting} proceeds, so the connect screen, handshake,
 * login and configuration phases are all in the timeline; it ends when the connection goes inactive
 * (unless a server transfer is in progress).
 */
public final class SessionManager {
    private static final Logger LOG = LoggerFactory.getLogger("InputReplay");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static Mode armedMode;
    private static Path armedReplayFile;
    private static boolean armedForcePhysical;
    private static RecordingReader armedReader;
    private static volatile Session session;
    private static volatile Connection connection;
    private static Path lastFinished;

    // per-tick change detection
    private static LocalPlayer lastPlayer;
    private static ClientLevel lastLevel;
    private static int lastWidth, lastHeight;
    private static float lastTickRate = -1;

    private SessionManager() {}

    public static Session session() {
        return session;
    }

    public static Mode armedMode() {
        return armedMode;
    }

    public static Path armedReplayFile() {
        return armedReplayFile;
    }

    public static Path lastFinished() {
        return lastFinished;
    }

    public static Path recordingsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("inputreplay").resolve("recordings");
    }

    public static void arm(Mode mode, Path replayFile, boolean forcePhysical) {
        armedMode = mode;
        armedReplayFile = replayFile;
        armedForcePhysical = forcePhysical;
        armedReader = null;
    }

    /** Arms a replay with a recording that was already loaded (and validated) by the list screen. */
    public static void armReplay(RecordingReader reader, boolean forcePhysical) {
        arm(Mode.REPLAY, reader.path, forcePhysical);
        armedReader = reader;
    }

    public static void disarm() {
        arm(null, null, false);
    }

    // ------------------------------------------------------------------ connect / disconnect

    /**
     * From {@code ConnectScreen.startConnecting} HEAD. Returns true to cancel the call (the mode
     * prompt was opened instead; it re-invokes startConnecting once a mode is chosen).
     */
    public static boolean onStartConnecting(Screen parent, Minecraft mc, ServerAddress address, ServerData data,
                                            boolean quickPlay, TransferState transfer) {
        Session s = session;
        if (transfer != null && s != null && !s.ended()) {
            s.transferPending = true;
            s.localDisconnect = false;
            s.serverDisconnectSeen = false;
            JsonObject d = new JsonObject();
            d.addProperty("address", address.toString());
            s.event("TRANSFER", d);
            return false;
        }
        if (armedMode == null) {
            if (Config.get().promptOnConnect) {
                mc.gui.setScreen(new me.biquaternions.inputreplay.client.gui.ModeSelectScreen(parent, address, data, quickPlay, transfer));
                return true;
            }
            armedMode = Mode.PLAIN;
        }
        Mode mode = armedMode;
        Path replayFile = armedReplayFile;
        boolean forcePhysical = armedForcePhysical;
        RecordingReader reader = armedReader;
        disarm();
        if (s != null && !s.ended()) endSession("superseded");
        if (mode == Mode.PLAIN) return false;
        try {
            if (mode == Mode.REPLAY && reader == null) {
                if (replayFile == null) throw new IllegalStateException("replay armed without a file");
                reader = new RecordingReader(resolveRecording(replayFile));
            }
            startSession(mc, mode, address, data, reader, forcePhysical);
        } catch (Exception e) {
            LOG.error("Could not start InputReplay {} session; connecting without recording or replay", mode, e);
            session = null;
        }
        return false;
    }

    private static void startSession(Minecraft mc, Mode mode, ServerAddress address, ServerData data,
                                     RecordingReader reader, boolean forcePhysical) throws Exception {
        String serverPart = address.getHost().replaceAll("[^A-Za-z0-9._-]", "_") + "_" + address.getPort();
        String base = LocalDateTime.now().format(FILE_TIME) + "_" + serverPart + (mode == Mode.REPLAY ? "_replay" : "");
        Path dir = recordingsDir();
        int n = 1;
        String unique = base;
        while (Files.exists(dir.resolve(unique + ".irr")) || Files.exists(dir.resolve(unique + ".irr.partial"))) {
            unique = base + "_" + (++n);
        }
        RecordingWriter writer = new RecordingWriter(dir, unique, Config.get().queueBytes);
        List<String> mappingNames = BindingResolver.allMappingNames();
        String options = BindingResolver.optionsSnapshot();

        JsonObject meta = new JsonObject();
        meta.addProperty("formatVersion", 1);
        meta.addProperty("generator", "InputReplay " + modVersion("inputreplay"));
        meta.addProperty("mcVersion", SharedConstants.getCurrentVersion().name());
        meta.addProperty("protocolVersion", SharedConstants.getProtocolVersion());
        meta.addProperty("loaderVersion", modVersion("fabricloader"));
        meta.addProperty("mode", mode == Mode.REPLAY ? "replay" : "record");
        if (mode == Mode.REPLAY) {
            meta.addProperty("replayOf", reader.path.getFileName().toString());
        }
        JsonObject server = new JsonObject();
        server.addProperty("address", address.toString());
        server.addProperty("name", data == null ? "" : data.name);
        meta.add("server", server);
        meta.addProperty("startedAt", Instant.now().toString());
        JsonObject window = new JsonObject();
        window.addProperty("width", mc.getWindow().getWidth());
        window.addProperty("height", mc.getWindow().getHeight());
        window.addProperty("guiScale", mc.getWindow().getGuiScale());
        window.addProperty("fullscreen", mc.options.fullscreen().get());
        meta.add("window", window);
        JsonArray names = new JsonArray();
        mappingNames.forEach(names::add);
        meta.add("keyMappingNames", names);
        meta.addProperty("optionsHash", sha256(options));
        JsonObject policy = new JsonObject();
        policy.addProperty("default", Config.get().defaultPacketPolicy);
        for (Map.Entry<String, String> e : Config.get().packetPolicy.entrySet()) policy.addProperty(e.getKey(), e.getValue());
        meta.add("packetPolicy", policy);
        meta.addProperty("summaryLimit", Config.get().summaryLimit);
        JsonArray mods = new JsonArray();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            if (c.getContainingMod().isPresent()) continue; // nested jars
            JsonObject m = new JsonObject();
            m.addProperty("id", c.getMetadata().getId());
            m.addProperty("version", c.getMetadata().getVersion().getFriendlyString());
            mods.add(m);
        }
        meta.add("modList", mods);
        meta.addProperty("overflow", false);

        Session s = new Session(mode, writer, meta, mappingNames);
        PacketCapture.reset();
        InputCapture.reset();
        lastPlayer = null;
        lastLevel = null;
        lastWidth = mc.getWindow().getWidth();
        lastHeight = mc.getWindow().getHeight();
        lastTickRate = -1;
        writer.writeText("options.txt", options);
        s.writeMetadata();
        JsonObject d = new JsonObject();
        d.addProperty("mode", mode.name());
        d.addProperty("address", address.toString());
        s.event("SESSION_START", d);
        session = s;
        if (mode == Mode.REPLAY) Replayer.attach(s, reader, forcePhysical);
        LOG.info("InputReplay {} session started: {}", mode, writer.partialDir());
    }

    /** From the ConnectScreen connect thread, right before the handshake is sent. */
    public static void onConnectionCreated(Connection conn) {
        Session s = session;
        if (s == null || s.ended()) return;
        connection = conn;
        s.channel = ((ConnectionAccessor) conn).inputreplay$getChannel();
        boolean transfer = s.transferPending;
        s.transferPending = false;
        JsonObject d = new JsonObject();
        d.addProperty("remote", String.valueOf(conn.getRemoteAddress()));
        d.addProperty("transfer", transfer);
        s.event("CONNECTED", d);
    }

    public static boolean isSessionConnection(Connection conn) {
        Session s = session;
        return s != null && !s.ended() && conn == connection;
    }

    /** Netty thread: channelActive of our connection (channel may be null at creation time). */
    public static void onChannelActive(Connection conn, io.netty.channel.Channel ch) {
        Session s = session;
        if (s != null && conn == connection) s.channel = ch;
    }

    /** Main thread: {@code Connection.disconnect} called on our connection. */
    public static void onLocalDisconnect(Connection conn, String reason) {
        Session s = session;
        if (s == null || conn != connection || s.ended()) return;
        if (!s.serverDisconnectSeen) s.localDisconnect = true;
        JsonObject d = new JsonObject();
        d.addProperty("reason", reason);
        d.addProperty("byServer", s.serverDisconnectSeen);
        s.event("DISCONNECT", d);
    }

    /** Netty thread: channelInactive of our connection. Ends the session on the main thread. */
    public static void onChannelInactive(Connection conn) {
        Session s = session;
        if (s == null || conn != connection) return;
        Minecraft.getInstance().execute(() -> {
            if (session != s || s.ended()) return;
            if (conn != connection) return; // old connection of a completed transfer
            if (s.transferPending) {
                s.event("TRANSFER_OLD_CONNECTION_CLOSED");
                return;
            }
            endSession(null);
        });
    }

    public static void endSession(String reason) {
        Session s = session;
        if (s == null || s.ended()) return;
        if (reason != null) s.setEndReason(reason);
        InputCapture.flushCursor(s);
        boolean discard = s.channel == null && connection == null;
        Replayer.onSessionEnd(s);
        s.finish(path -> {
            if (discard) {
                LOG.info("Connection never established; discarding {}", path);
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            } else {
                lastFinished = path;
            }
        });
        LOG.info("InputReplay session ended ({})", s.endReason());
        session = null;
        connection = null;
        PacketCapture.reset();
        InputCapture.reset();
    }

    // ------------------------------------------------------------------ per frame / per tick

    /** {@code Minecraft.runTick} HEAD. */
    public static void onFrame() {
        Replayer.onFrame(Minecraft.getInstance());
        Session s = session;
        if (s == null) return;
        s.clock.newFrame();
        InputCapture.flushCursor(s);
    }

    public static void onTickStart(Minecraft mc) {
        Session s = session;
        if (s == null || s.ended()) return;
        int frames = s.clock.framesSinceLastTick();
        int tick = s.clock.startTick();
        s.lastTick = tick;
        InputCapture.flushCursor(s);
        Screen screen = mc.gui.screen();
        s.emit(Stream.INPUTS, Records.TICK_MARK, new Bin(48).uvarint(frames).str(screen == null ? "" : screen.getClass().getName()));

        ClientPacketListener cpl = mc.getConnection();
        if (!s.clock.anchored() && cpl != null && cpl.hasClientLoaded() && mc.player != null) {
            s.clock.setAnchor(tick);
            long seq = s.clock.peekSeq();
            JsonObject d = new JsonObject();
            d.addProperty("rawTick", tick);
            d.addProperty("anchorSeq", seq);
            s.event("PLAYER_LOADED", d);
            s.metadata.addProperty("anchorTick", tick);
            s.metadata.addProperty("anchorSeq", seq);
            s.writeMetadata();
        }
        detectChanges(s, mc);
        if (s.channel == null && connection == null && !(screen instanceof ConnectScreen) && tick > 20) {
            endSession("connect_failed");
            return;
        }
        Replayer.beforeTick(mc, s, tick);
    }

    private static void detectChanges(Session s, Minecraft mc) {
        if (mc.player != lastPlayer) {
            if (mc.player != null && lastPlayer != null) {
                JsonObject d = new JsonObject();
                d.addProperty("entityId", mc.player.getId());
                s.event("RESPAWN", d);
            }
            lastPlayer = mc.player;
        }
        if (mc.level != lastLevel) {
            if (mc.level != null) {
                JsonObject d = new JsonObject();
                d.addProperty("dimension", mc.level.dimension().identifier().toString());
                s.event("DIMENSION_CHANGE", d);
            }
            lastLevel = mc.level;
        }
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w;
            lastHeight = h;
            JsonObject d = new JsonObject();
            d.addProperty("width", w);
            d.addProperty("height", h);
            d.addProperty("guiScale", mc.getWindow().getGuiScale());
            s.event("WINDOW_RESIZE", d);
        }
        if (mc.level != null) {
            float rate = mc.level.tickRateManager().tickrate();
            boolean frozen = mc.level.tickRateManager().isFrozen();
            float key = frozen ? -rate : rate;
            if (key != lastTickRate) {
                if (lastTickRate != -1) {
                    JsonObject d = new JsonObject();
                    d.addProperty("tickrate", rate);
                    d.addProperty("frozen", frozen);
                    s.event("TICKRATE_CHANGE", d);
                }
                lastTickRate = key;
            }
        }
    }

    public static void onTickEnd(Minecraft mc) {
        Session s = session;
        if (s == null || s.ended()) return;
        LocalPlayer p = mc.player;
        if (p != null) {
            s.emit(Stream.SNAPSHOTS, Records.SNAPSHOT, PlayerSnapshotter.snapshot(mc, p));
        }
        s.emit(Stream.INPUTS, Records.KEY_STATE, keyState(s, p));
        Replayer.afterTick(mc, s, s.lastTick);
        s.clock.endTick();
        if (s.writer.takeOverflowNotice()) {
            JsonObject d = new JsonObject();
            d.addProperty("dropped", s.writer.droppedRecords());
            s.event("QUEUE_OVERFLOW", d);
        }
        if (s.lastTick % 100 == 0) PacketCapture.cleanup(s.lastTick);
    }

    private static Bin keyState(Session s, LocalPlayer p) {
        Map<String, KeyMapping> all = KeyMappingAccessor.inputreplay$getAll();
        List<String> names = s.keyMappingNames;
        byte[] bits = new byte[(names.size() + 7) / 8];
        for (int i = 0; i < names.size(); i++) {
            KeyMapping m = all.get(names.get(i));
            if (m != null && m.isDown()) bits[i >> 3] |= (byte) (1 << (i & 7));
        }
        return new Bin(bits.length + 8).bytes(bits).u8(p == null || p.input == null ? 0 : PlayerSnapshotter.inputBits(p.input.keyPresses));
    }

    public static void onScreenChange(Screen old, Screen next) {
        Session s = session;
        if (s == null || s.ended() || old == next) return;
        if (old != null) {
            JsonObject d = new JsonObject();
            d.addProperty("screen", old.getClass().getName());
            s.event("SCREEN_CLOSE", d);
        }
        if (next != null) {
            JsonObject d = new JsonObject();
            d.addProperty("screen", next.getClass().getName());
            s.event("SCREEN_OPEN", d);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Absolute path, or a file name / relative path inside the recordings directory. */
    public static Path resolveRecording(Path p) {
        if (p.isAbsolute() || Files.exists(p)) return p;
        return recordingsDir().resolve(p.toString());
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
}
