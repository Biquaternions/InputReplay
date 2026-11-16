package me.biquaternions.inputreplay.client.session;

import com.mojang.blaze3d.platform.InputConstants;
import me.biquaternions.inputreplay.client.mixin.client.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Physical key ↔ key-mapping name resolution, and the {@code options.txt} snapshot stored in each
 * recording. Format: one {@code key:<mapping>=<key name>} or {@code option:<name>=<value>} per line.
 */
public final class BindingResolver {
    private static final List<String> NO_NAMES = List.of();

    private BindingResolver() {}

    /** Names of every key mapping bound to the given key right now, sorted; empty if none. */
    public static List<String> mappingNames(InputConstants.Key key) {
        List<KeyMapping> list = KeyMappingAccessor.inputreplay$getMap().get(key);
        if (list == null || list.isEmpty()) return NO_NAMES;
        List<String> names = new ArrayList<>(list.size());
        for (KeyMapping m : list) names.add(m.getName());
        if (names.size() > 1) Collections.sort(names);
        return names;
    }

    /** All registered key mapping names, sorted (the KEY_STATE bit order). */
    public static List<String> allMappingNames() {
        return new ArrayList<>(new TreeSet<>(KeyMappingAccessor.inputreplay$getAll().keySet()));
    }

    /** Current binding table: mapping name → key name (e.g. {@code key.keyboard.w}). */
    public static Map<String, String> currentBindings() {
        Map<String, String> out = new TreeMap<>();
        for (KeyMapping m : Minecraft.getInstance().options.keyMappings) out.put(m.getName(), m.saveString());
        return out;
    }

    public static Map<String, String> currentOptions() {
        Options o = Minecraft.getInstance().options;
        Map<String, String> out = new LinkedHashMap<>();
        out.put("sensitivity", String.valueOf(o.sensitivity().get()));
        out.put("invertMouseX", String.valueOf(o.invertMouseX().get()));
        out.put("invertMouseY", String.valueOf(o.invertMouseY().get()));
        out.put("toggleCrouch", String.valueOf(o.toggleCrouch().get()));
        out.put("toggleSprint", String.valueOf(o.toggleSprint().get()));
        out.put("toggleAttack", String.valueOf(o.toggleAttack().get()));
        out.put("toggleUse", String.valueOf(o.toggleUse().get()));
        out.put("autoJump", String.valueOf(o.autoJump().get()));
        out.put("smoothCamera", String.valueOf(o.smoothCamera));
        out.put("guiScale", String.valueOf(o.guiScale().get()));
        out.put("fov", String.valueOf(o.fov().get()));
        return out;
    }

    public static String optionsSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("# InputReplay options snapshot\n");
        for (Map.Entry<String, String> e : currentBindings().entrySet()) {
            sb.append("key:").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        for (Map.Entry<String, String> e : currentOptions().entrySet()) {
            sb.append("option:").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** Parsed {@code options.txt}: bindings and options. */
    public record Snapshot(Map<String, String> bindings, Map<String, String> options) {}

    public static Snapshot parse(String text) {
        Map<String, String> bindings = new TreeMap<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1);
            if (k.startsWith("key:")) bindings.put(k.substring(4), v);
            else if (k.startsWith("option:")) options.put(k.substring(7), v);
        }
        return new Snapshot(bindings, options);
    }

    /** Mapping names whose binding differs (or that exist on only one side). */
    public static List<String> bindingDifferences(Map<String, String> recorded, Map<String, String> current) {
        TreeSet<String> names = new TreeSet<>(recorded.keySet());
        names.addAll(current.keySet());
        List<String> diff = new ArrayList<>();
        for (String n : names) {
            if (!java.util.Objects.equals(recorded.get(n), current.get(n))) diff.add(n);
        }
        return diff;
    }

    /** Option names whose value differs and that affect replay (toggle modes, auto-jump). */
    public static List<String> relevantOptionDifferences(Map<String, String> recorded, Map<String, String> current) {
        List<String> diff = new ArrayList<>();
        for (String n : List.of("toggleCrouch", "toggleSprint", "toggleAttack", "toggleUse", "autoJump", "guiScale")) {
            if (recorded.containsKey(n) && !recorded.get(n).equals(current.get(n))) diff.add(n);
        }
        return diff;
    }
}
