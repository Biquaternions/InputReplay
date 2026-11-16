package me.biquaternions.inputreplay.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.biquaternions.inputreplay.client.io.RecordingExporter;
import me.biquaternions.inputreplay.client.replay.Replayer;
import net.minecraft.commands.SharedSuggestionProvider;
import com.mojang.brigadier.context.CommandContext;
import me.biquaternions.inputreplay.client.gui.SessionHud;
import me.biquaternions.inputreplay.client.session.Mode;
import me.biquaternions.inputreplay.client.session.Session;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class InputreplayClient implements ClientModInitializer {
    public static final String MOD_ID = "inputreplay";
    private static final Logger LOG = LoggerFactory.getLogger("InputReplay");
    private static String autoConnect;

    @Override
    public void onInitializeClient() {
        Config.load();
        ClientTickEvents.START_CLIENT_TICK.register(SessionManager::onTickStart);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            SessionManager.onTickEnd(mc);
            tryAutoConnect(mc);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> SessionManager.endSession("client_stopping"));
        if (Boolean.getBoolean("inputreplay.auditMixins")) {
            // Dev check: load every mixin target now so a broken injection fails at startup.
            ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
                org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
                LOG.info("InputReplay mixin audit finished");
            });
        }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "session_hud"), SessionHud::extract);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> registerCommands(dispatcher));
        readSystemProperties();
    }

    /** {@code -Dinputreplay.mode=record|replay|plain -Dinputreplay.file=<path> -Dinputreplay.autoconnect=host:port} */
    private static void readSystemProperties() {
        String mode = System.getProperty("inputreplay.mode");
        if (mode != null) {
            switch (mode.toLowerCase()) {
                case "record" -> SessionManager.arm(Mode.RECORD, null, false);
                case "plain" -> SessionManager.arm(Mode.PLAIN, null, false);
                case "replay" -> {
                    String file = System.getProperty("inputreplay.file");
                    if (file == null) LOG.error("inputreplay.mode=replay needs -Dinputreplay.file=<path>");
                    else SessionManager.arm(Mode.REPLAY, Path.of(file), false);
                }
                default -> LOG.error("Unknown inputreplay.mode '{}'", mode);
            }
        }
        autoConnect = System.getProperty("inputreplay.autoconnect");
    }

    private static void tryAutoConnect(Minecraft mc) {
        if (autoConnect == null || !(mc.gui.screen() instanceof TitleScreen title)) return;
        String target = autoConnect;
        autoConnect = null;
        LOG.info("Auto-connecting to {}", target);
        ConnectScreen.startConnecting(title, mc, ServerAddress.parseString(target),
                new ServerData("InputReplay autoconnect", target, ServerData.Type.OTHER), false, null);
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("inputreplay")
                .then(literal("record").executes(c -> arm(c, Mode.RECORD)))
                .then(literal("plain").executes(c -> arm(c, Mode.PLAIN)))
                .then(literal("replay")
                        .then(argument("file", StringArgumentType.string())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(recordingNames(), b))
                                .executes(c -> armReplay(c, false))
                                .then(literal("physical").executes(c -> armReplay(c, true)))))
                .then(literal("export")
                        .then(argument("file", StringArgumentType.string())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(recordingNames(), b))
                                .executes(c -> export(c, "all", ""))
                                .then(argument("stream", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(RecordingExporter.streams(), b))
                                        .executes(c -> export(c, StringArgumentType.getString(c, "stream"), ""))
                                        .then(argument("classes", StringArgumentType.greedyString())
                                                .executes(c -> export(c, StringArgumentType.getString(c, "stream"),
                                                        StringArgumentType.getString(c, "classes")))))))
                .then(literal("stop").executes(c -> {
                    if (Replayer.active() != null) {
                        Replayer.active().abort("aborted");
                    } else if (SessionManager.session() != null) {
                        SessionManager.endSession("stopped_by_command");
                    } else {
                        c.getSource().sendError(Component.literal("No session"));
                        return 0;
                    }
                    c.getSource().sendFeedback(Component.literal("InputReplay: session ended (still connected)"));
                    return 1;
                }))
                .then(literal("off").executes(c -> {
                    SessionManager.disarm();
                    c.getSource().sendFeedback(Component.literal("InputReplay: disarmed (the prompt will be shown on connect if enabled)"));
                    return 1;
                }))
                .then(literal("prompt")
                        .then(literal("on").executes(c -> setPrompt(c, true)))
                        .then(literal("off").executes(c -> setPrompt(c, false))))
                .then(literal("hud").executes(c -> {
                    Config.get().hudEnabled = !Config.get().hudEnabled;
                    Config.save();
                    c.getSource().sendFeedback(Component.literal("InputReplay HUD " + (Config.get().hudEnabled ? "on" : "off")));
                    return 1;
                }))
                .then(literal("status").executes(InputreplayClient::status))
                .then(literal("pipeline").executes(c -> {
                    Session s = SessionManager.session();
                    if (s == null || s.channel == null) {
                        c.getSource().sendError(Component.literal("No captured connection"));
                        return 0;
                    }
                    c.getSource().sendFeedback(Component.literal("Pipeline: " + String.join(", ", s.channel.pipeline().names())));
                    return 1;
                })));
    }

    private static int arm(CommandContext<FabricClientCommandSource> c, Mode mode) {
        SessionManager.arm(mode, null, false);
        c.getSource().sendFeedback(Component.literal("InputReplay: armed " + mode + " for the next connection"));
        return 1;
    }

    private static int armReplay(CommandContext<FabricClientCommandSource> c, boolean physical) {
        Path p = SessionManager.resolveRecording(Path.of(StringArgumentType.getString(c, "file")));
        if (!Files.exists(p)) {
            c.getSource().sendError(Component.literal("No such recording: " + p));
            return 0;
        }
        SessionManager.arm(Mode.REPLAY, p, physical);
        c.getSource().sendFeedback(Component.literal("InputReplay: armed replay of " + p.getFileName()
                + (physical ? " (physical keys)" : "") + " for the next connection"));
        return 1;
    }

    /**
     * {@code /inputreplay export <file> [stream] [classes]}: JSONL next to the recording, same format as
     * {@code irr_dump.py dump --format jsonl}. {@code classes}: comma-separated globs, {@code !glob} excludes.
     */
    private static int export(CommandContext<FabricClientCommandSource> c, String stream, String classes) {
        Path p = SessionManager.resolveRecording(Path.of(StringArgumentType.getString(c, "file")));
        if (!Files.exists(p)) {
            c.getSource().sendError(Component.literal("No such recording: " + p));
            return 0;
        }
        if (!RecordingExporter.streams().contains(stream)) {
            c.getSource().sendError(Component.literal("Unknown stream " + stream + "; one of " + RecordingExporter.streams()));
            return 0;
        }
        List<String> inc = new java.util.ArrayList<>(), exc = new java.util.ArrayList<>();
        for (String g : classes.split(",")) {
            g = g.strip();
            if (g.isEmpty()) continue;
            if (g.startsWith("!")) exc.add(g.substring(1));
            else inc.add(g);
        }
        String base = p.getFileName().toString().replaceAll("\\.irr(\\.partial)?$", "");
        Path out = p.resolveSibling(base + "." + stream + ".jsonl");
        RecordingExporter.Options opt = new RecordingExporter.Options(stream, inc, exc, true);
        c.getSource().sendFeedback(Component.literal("InputReplay: exporting " + p.getFileName() + " ..."));
        Minecraft mc = c.getSource().getClient();
        Thread t = new Thread(() -> {
            String msg;
            try {
                long n = RecordingExporter.export(p, out, opt);
                msg = "InputReplay: exported " + n + " records to " + out;
            } catch (Exception e) {
                LOG.error("Export of {} failed", p, e);
                msg = "InputReplay: export failed: " + e.getMessage();
            }
            String m = msg;
            mc.execute(() -> {
                if (mc.player != null) mc.player.sendSystemMessage(Component.literal(m));
            });
        }, "InputReplay-Export");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static List<String> recordingNames() {
        try (Stream<Path> s = Files.list(SessionManager.recordingsDir())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".irr") || n.endsWith(".irr.partial"))
                    .map(n -> '"' + n + '"').sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int setPrompt(CommandContext<FabricClientCommandSource> c, boolean on) {
        Config.get().promptOnConnect = on;
        Config.save();
        c.getSource().sendFeedback(Component.literal("InputReplay: connect prompt " + (on ? "enabled" : "disabled")));
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> c) {
        Session s = SessionManager.session();
        StringBuilder sb = new StringBuilder("InputReplay: ");
        if (s == null) sb.append("no session");
        else sb.append(s.mode).append(" session, writing ").append(s.writer.partialDir().getFileName())
                .append(", tick ").append(s.clock.anchored(s.lastTick));
        sb.append("; armed: ").append(SessionManager.armedMode() == null ? "none" : SessionManager.armedMode());
        if (SessionManager.lastFinished() != null) sb.append("; last file: ").append(SessionManager.lastFinished().getFileName());
        c.getSource().sendFeedback(Component.literal(sb.toString()));
        return 1;
    }
}
