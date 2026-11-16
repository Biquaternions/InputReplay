package me.biquaternions.inputreplay.client.capture;

import me.biquaternions.inputreplay.client.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class name, readable summary and payload policy for captured packets.
 * <p>
 * Most packets are records, whose {@code toString()} lists every field. Some important ones are
 * not (e.g. {@code ServerboundMovePlayerPacket}, {@code ServerboundPlayerCommandPacket}); for any class
 * that does not override {@code toString()}, the summary is built reflectively as
 * {@code Name[field=value, ...]} over the declared instance fields of the class hierarchy.
 */
public final class PacketSummarizer {
    private PacketSummarizer() {}

    public enum Policy { FULL, SUMMARY, SKIP }

    private static final Map<Class<?>, List<Field>> REFLECTIVE = new ConcurrentHashMap<>();
    private static final List<Field> USE_TO_STRING = List.of();
    private static final Map<Class<?>, String> NAMES = new ConcurrentHashMap<>();

    public static Policy policy(String className) {
        return switch (Config.get().policyFor(className).toLowerCase()) {
            case "summary" -> Policy.SUMMARY;
            case "skip" -> Policy.SKIP;
            default -> Policy.FULL;
        };
    }

    /** Binary name without the package, e.g. {@code ServerboundMovePlayerPacket$PosRot}. */
    public static String className(Object packet) {
        return NAMES.computeIfAbsent(packet.getClass(), c -> {
            String n = c.getName();
            return n.substring(n.lastIndexOf('.') + 1);
        });
    }

    public static String summary(Object packet) {
        String s;
        try {
            List<Field> fields = REFLECTIVE.computeIfAbsent(packet.getClass(), PacketSummarizer::reflectiveFields);
            s = fields == USE_TO_STRING ? String.valueOf(packet) : reflective(packet, fields);
        } catch (Throwable t) {
            s = "<summary failed: " + t + ">";
        }
        int limit = Config.get().summaryLimit;
        if (s.length() > limit) s = s.substring(0, limit) + "…[" + s.length() + " chars]";
        return s;
    }

    private static List<Field> reflectiveFields(Class<?> c) {
        try {
            if (c.getMethod("toString").getDeclaringClass() != Object.class) return USE_TO_STRING;
        } catch (NoSuchMethodException e) {
            return USE_TO_STRING;
        }
        List<Field> out = new ArrayList<>();
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) chain.addFirst(k);
        for (Class<?> k : chain) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out;
    }

    private static String reflective(Object packet, List<Field> fields) throws IllegalAccessException {
        StringBuilder sb = new StringBuilder(className(packet)).append('[');
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (i > 0) sb.append(", ");
            sb.append(f.getName()).append('=');
            Object v = f.get(packet);
            if (v instanceof byte[] b) sb.append("byte[").append(b.length).append(']');
            else if (v != null && v.getClass().isArray()) sb.append(v.getClass().getComponentType().getSimpleName()).append("[…]");
            else sb.append(v);
        }
        return sb.append(']').toString();
    }

    /** Reads the leading varint packet id of an encoded packet; -1 if malformed. */
    public static int packetId(byte[] b) {
        int value = 0;
        for (int i = 0; i < Math.min(5, b.length); i++) {
            value |= (b[i] & 0x7F) << (7 * i);
            if ((b[i] & 0x80) == 0) return value;
        }
        return -1;
    }
}
