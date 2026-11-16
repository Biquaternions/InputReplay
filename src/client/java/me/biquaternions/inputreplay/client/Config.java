package me.biquaternions.inputreplay.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** User configuration, stored as {@code config/inputreplay.json}. */
public final class Config {
    private static final Logger LOG = LoggerFactory.getLogger("InputReplay/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance = new Config();

    /** Show the record/replay/plain prompt before connecting to a server. */
    public boolean promptOnConnect = true;
    /** Key code (as in {@code InputConstants.KEY_*} / {@code KeyEvent.key()}) that aborts a running replay. Default F10. */
    public int abortKey = 67;
    /** Show the session HUD. */
    public boolean hudEnabled = true;
    /** Default payload policy for every packet class: {@code full}, {@code summary} or {@code skip}. */
    public String defaultPacketPolicy = "full";
    /**
     * Per-class overrides, keyed by package-less class name such as {@code ServerboundMovePlayerPacket$Pos}
     * (glob with {@code *} allowed), e.g.
     * {@code "ClientboundLevelChunkWithLightPacket": "summary"}. Empty by default: everything is full.
     */
    public Map<String, String> packetPolicy = new LinkedHashMap<>();
    /** Maximum length of the {@code toString()} summary stored per packet. */
    public int summaryLimit = 16 * 1024;
    /** Pending bytes the writer queue accepts before dropping records. */
    public long queueBytes = 64L * 1024 * 1024;
    /** Freeze-free drift warning threshold for the replay HUD, in blocks. */
    public double driftThreshold = 0.001;
    /** Pause the replay when position drift exceeds {@link #driftThreshold}. */
    public boolean pauseOnDrift = false;
    /** Abort a replay when {@code minecraft.player} has been null for this many ticks. */
    public int maxNullPlayerTicks = 200;

    public static Config get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("inputreplay.json");
    }

    public static void load() {
        Path p = path();
        if (Files.exists(p)) {
            try {
                Config c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Config.class);
                if (c != null) instance = c;
                if (instance.packetPolicy == null) instance.packetPolicy = new LinkedHashMap<>();
            } catch (Exception e) {
                LOG.error("Could not read {}; using defaults", p, e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Could not write {}", path(), e);
        }
    }

    /** Resolves the payload policy for a packet class (simple name). */
    public String policyFor(String simpleName) {
        if (packetPolicy.isEmpty()) return defaultPacketPolicy;
        String exact = packetPolicy.get(simpleName);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : packetPolicy.entrySet()) {
            if (e.getKey().contains("*") && globMatches(e.getKey(), simpleName)) return e.getValue();
        }
        return defaultPacketPolicy;
    }

    static boolean globMatches(String glob, String s) {
        StringBuilder re = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') re.append(".*");
            else if (c == '?') re.append('.');
            else re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return s.matches(re.toString());
    }
}
