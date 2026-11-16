package me.biquaternions.inputreplay.client.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JSONL export of a recording, with the same records and field names as {@code irr_dump.py dump
 * --format jsonl} (PACKET_IN records carry the joined apply stamp; PACKET_APPLY records are omitted;
 * {@code all} is ordered by seq). Pure Java: used by {@code /inputreplay export} and testable
 * without Minecraft. Keep in sync with {@code decode_body} in irr_dump.py.
 */
public final class RecordingExporter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String[] POSES = {"STANDING", "FALL_FLYING", "SLEEPING", "SWIMMING", "SPIN_ATTACK", "CROUCHING",
            "LONG_JUMPING", "DYING", "CROAKING", "USING_TONGUE", "SITTING", "ROARING", "SNIFFING", "EMERGING", "DIGGING",
            "SLIDING", "SHOOTING", "INHALING"};
    private static final String[] HIT_TYPES = {"NONE", "MISS", "BLOCK", "ENTITY"};
    private static final String[] INPUT_BITS = {"forward", "backward", "left", "right", "jump", "shift", "sprint"};
    private static final String[] SNAP_FLAGS = {"onGround", "horizontalCollision", "verticalCollision", "sprinting",
            "swimming", "fallFlying", "shiftKeyDown", "crouching", "usingItem", "minorHorizontalCollision"};
    private static final Map<String, String> STREAM_FILES = new LinkedHashMap<>();
    private static final Map<Integer, String> TYPE_NAMES = new HashMap<>();

    static {
        STREAM_FILES.put("inputs", Records.Stream.INPUTS.fileName);
        STREAM_FILES.put("in", Records.Stream.PACKETS_IN.fileName);
        STREAM_FILES.put("out", Records.Stream.PACKETS_OUT.fileName);
        STREAM_FILES.put("snapshots", Records.Stream.SNAPSHOTS.fileName);
        STREAM_FILES.put("events", Records.Stream.EVENTS.fileName);
        String[][] names = {{"1", "KEY"}, {"2", "TEXT"}, {"3", "CHAR"}, {"4", "MOUSE_BUTTON"}, {"5", "SCROLL"},
                {"6", "CURSOR"}, {"7", "TURN"}, {"8", "TICK_MARK"}, {"9", "KEY_STATE"}, {"20", "PACKET_IN"},
                {"21", "PACKET_APPLY"}, {"22", "PACKET_OUT"}, {"30", "SNAPSHOT"}, {"40", "EVENT"}};
        for (String[] n : names) TYPE_NAMES.put(Integer.parseInt(n[0]), n[1]);
    }

    public record Options(String stream, List<String> includeClasses, List<String> excludeClasses, boolean payload) {
        public static Options all() {
            return new Options("all", List.of(), List.of(), true);
        }
    }

    private record Row(long seq, JsonObject json) {}

    private RecordingExporter() {}

    public static List<String> streams() {
        List<String> l = new ArrayList<>(STREAM_FILES.keySet());
        l.add("all");
        return l;
    }

    /** Exports to {@code out}; returns the number of records written. */
    public static long export(Path recording, Path out, Options opt) throws IOException {
        if (!opt.stream().equals("all") && !STREAM_FILES.containsKey(opt.stream())) {
            throw new IllegalArgumentException("Unknown stream " + opt.stream() + " (expected " + streams() + ")");
        }
        List<Pattern> inc = opt.includeClasses().stream().map(RecordingExporter::glob).toList();
        List<Pattern> exc = opt.excludeClasses().stream().map(RecordingExporter::glob).toList();
        try (RecordingReader.Source src = RecordingReader.Source.open(recording)) {
            byte[] metaBytes = src.read("metadata.json");
            JsonObject meta = metaBytes == null ? new JsonObject()
                    : JsonParser.parseString(new String(metaBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement a = meta.get("anchorTick");
            Integer anchor = a == null || a.isJsonNull() ? null : a.getAsInt();
            if (anchor == null) anchor = RecordingReader.anchorFromEvents(RecordingReader.gunzip(src.read(Records.Stream.EVENTS.fileName)));
            int anchorTick = anchor == null ? 0 : anchor;
            JsonArray keyNames = meta.has("keyMappingNames") ? meta.getAsJsonArray("keyMappingNames") : new JsonArray();

            List<Row> rows = new ArrayList<>();
            for (Map.Entry<String, String> e : STREAM_FILES.entrySet()) {
                String stream = e.getKey();
                if (!opt.stream().equals("all") && !opt.stream().equals(stream)) continue;
                byte[] data = RecordingReader.gunzip(src.read(e.getValue()));
                Map<Long, JsonObject> applies = new HashMap<>();
                List<Row> streamRows = new ArrayList<>();
                RecordingReader.forEachFull(data, (type, seq, t, rawTick, frame, f, start, end) -> {
                    JsonObject j = new JsonObject();
                    j.addProperty("stream", stream);
                    j.addProperty("type", TYPE_NAMES.getOrDefault(type, "TYPE_" + type));
                    j.addProperty("seq", seq);
                    j.addProperty("tick", rawTick - anchorTick);
                    j.addProperty("rawTick", rawTick);
                    j.addProperty("frame", frame);
                    j.addProperty("tMs", Math.round(t / 1000.0) / 1000.0);
                    try {
                        decodeBody(type, f, keyNames, j, opt.payload());
                    } catch (RuntimeException ex) {
                        j.addProperty("error", "undecodable body");
                    }
                    if (type == Records.PACKET_APPLY) {
                        applies.put(j.get("pid").getAsLong(), j);
                        return;
                    }
                    streamRows.add(new Row(seq, j));
                });
                for (Row r : streamRows) {
                    JsonObject j = r.json();
                    if (stream.equals("in") && j.has("pid")) {
                        JsonObject ap = applies.get(j.get("pid").getAsLong());
                        j.add("applyTick", ap == null ? null : ap.get("tick"));
                        j.add("applyFrame", ap == null ? null : ap.get("frame"));
                        j.add("applySeq", ap == null ? null : ap.get("seq"));
                    }
                    if (keep(j, stream, inc, exc)) rows.add(r);
                }
            }
            if (opt.stream().equals("all")) rows.sort((x, y) -> Long.compare(x.seq(), y.seq()));
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                for (Row r : rows) {
                    w.write(GSON.toJson(r.json()));
                    w.write('\n');
                }
            }
            return rows.size();
        }
    }

    /** {@code java -cp <client classes>:<gson> ...RecordingExporter <recording> <out.jsonl> [stream]} (used by the tests). */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: RecordingExporter <recording.irr|dir> <out.jsonl> [stream]");
            System.exit(2);
        }
        Options o = new Options(args.length > 2 ? args[2] : "all", List.of(), List.of(), true);
        System.out.println(export(Path.of(args[0]), Path.of(args[1]), o));
    }

    private static boolean keep(JsonObject j, String stream, List<Pattern> inc, List<Pattern> exc) {
        JsonElement c = j.get("className");
        if (c == null) return inc.isEmpty() || !(stream.equals("in") || stream.equals("out"));
        String cls = c.getAsString();
        if (!inc.isEmpty() && inc.stream().noneMatch(p -> p.matcher(cls).matches())) return false;
        return exc.stream().noneMatch(p -> p.matcher(cls).matches());
    }

    static Pattern glob(String g) {
        StringBuilder re = new StringBuilder();
        for (char ch : g.toCharArray()) {
            if (ch == '*') re.append(".*");
            else if (ch == '?') re.append('.');
            else re.append(Pattern.quote(String.valueOf(ch)));
        }
        return Pattern.compile(re.toString());
    }

    private static String action(long a) {
        return a == 1 ? "press" : a == 0 ? "release" : a == -1 ? "repeat" : String.valueOf(a);
    }

    private static JsonArray bits(long bits, String[] names) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < names.length; i++) if ((bits & (1L << i)) != 0) arr.add(names[i]);
        return arr;
    }

    private static JsonArray strs(List<String> l) {
        JsonArray arr = new JsonArray();
        l.forEach(arr::add);
        return arr;
    }

    private static void decodeBody(int type, BinReader r, JsonArray keyNames, JsonObject j, boolean payload) {
        switch (type) {
            case Records.KEY -> {
                j.addProperty("action", action(r.svarint()));
                j.addProperty("key", r.svarint());
                j.addProperty("scancode", r.svarint());
                j.addProperty("modifiers", r.svarint());
                j.add("mappingNames", strs(r.strs()));
            }
            case Records.TEXT -> j.addProperty("text", r.str());
            case Records.CHAR -> j.addProperty("codepoint", r.svarint());
            case Records.MOUSE_BUTTON -> {
                j.addProperty("action", action(r.svarint()));
                j.addProperty("button", r.svarint());
                j.addProperty("modifiers", r.svarint());
                j.addProperty("x", r.f64());
                j.addProperty("y", r.f64());
                j.add("mappingNames", strs(r.strs()));
            }
            case Records.SCROLL, Records.TURN -> {
                j.addProperty("dx", r.f64());
                j.addProperty("dy", r.f64());
            }
            case Records.CURSOR -> {
                j.addProperty("x", r.f64());
                j.addProperty("y", r.f64());
                j.addProperty("dx", r.f64());
                j.addProperty("dy", r.f64());
            }
            case Records.TICK_MARK -> {
                j.addProperty("framesSinceLastTick", r.uvarint());
                String screen = r.str();
                j.addProperty("screen", screen.isEmpty() ? null : screen);
            }
            case Records.KEY_STATE -> {
                byte[] b = r.bytes();
                JsonArray down = new JsonArray();
                for (int i = 0; i < keyNames.size(); i++) {
                    if ((i >> 3) < b.length && (b[i >> 3] & (1 << (i & 7))) != 0) down.add(keyNames.get(i));
                }
                j.add("down", down);
                j.add("input", bits(r.u8(), INPUT_BITS));
            }
            case Records.PACKET_IN -> {
                j.addProperty("pid", r.uvarint());
                j.addProperty("phase", r.str());
                j.addProperty("packetId", r.svarint());
                j.addProperty("className", r.str());
                j.addProperty("bundleIndex", r.svarint());
                j.addProperty("size", r.uvarint());
                packetContent(r, j, payload);
            }
            case Records.PACKET_APPLY -> j.addProperty("pid", r.uvarint());
            case Records.PACKET_OUT -> {
                j.addProperty("phase", r.str());
                j.addProperty("packetId", r.svarint());
                j.addProperty("className", r.str());
                j.addProperty("size", r.uvarint());
                j.addProperty("sendStamped", r.bool());
                packetContent(r, j, payload);
            }
            case Records.SNAPSHOT -> {
                j.addProperty("x", r.f64());
                j.addProperty("y", r.f64());
                j.addProperty("z", r.f64());
                j.addProperty("yRot", (double) r.f32());
                j.addProperty("xRot", (double) r.f32());
                j.addProperty("yHeadRot", (double) r.f32());
                j.addProperty("vx", r.f64());
                j.addProperty("vy", r.f64());
                j.addProperty("vz", r.f64());
                j.add("flags", bits(r.uvarint(), SNAP_FLAGS));
                int pose = r.u8();
                if (pose < POSES.length) j.addProperty("pose", POSES[pose]);
                else j.addProperty("pose", pose);
                j.addProperty("fallDistance", r.f64());
                j.addProperty("jumpRidingScale", (double) r.f32());
                j.addProperty("sprintTriggerTime", r.svarint());
                j.addProperty("vehicleId", r.svarint());
                j.add("input", bits(r.u8(), INPUT_BITS));
                j.addProperty("moveX", (double) r.f32());
                j.addProperty("moveY", (double) r.f32());
                j.add("lastSentInput", bits(r.u8(), INPUT_BITS));
                int ht = r.u8();
                if (ht < HIT_TYPES.length) j.addProperty("hitType", HIT_TYPES[ht]);
                else j.addProperty("hitType", ht);
                j.addProperty("hitX", r.f64());
                j.addProperty("hitY", r.f64());
                j.addProperty("hitZ", r.f64());
                j.addProperty("hitEntityId", r.svarint());
                j.addProperty("selectedSlot", r.svarint());
                j.addProperty("heldItem", r.str());
            }
            case Records.EVENT -> {
                j.addProperty("name", r.str());
                JsonElement details;
                try {
                    details = JsonParser.parseString(r.str());
                } catch (RuntimeException e) {
                    details = new JsonObject();
                }
                j.add("details", details);
            }
            default -> {
            }
        }
    }

    private static void packetContent(BinReader r, JsonObject j, boolean payload) {
        j.addProperty("payloadMode", r.u8());
        j.addProperty("summary", r.str());
        byte[] p = r.bytes();
        if (payload) j.addProperty("payload", HexFormat.of().formatHex(p));
    }
}
