package me.biquaternions.inputreplay.client.capture;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which inbound packets change the local player's physics state. Used by the packet-sync replay
 * mode, and mirrored by {@code PLAYER_BY_ID} / {@code PLAYER_ALWAYS} in irr_dump.py (keep in sync).
 * <p>
 * Classification works on the recorded class name and summary only, so the live run and the source
 * recording are classified by exactly the same rule.
 */
public final class PlayerPackets {
    private PlayerPackets() {}

    /** Packets about an entity, given by the first field ({@code id} / {@code entityId}); only counted when it is the player. */
    public static final Set<String> BY_ID = Set.of(
            "ClientboundSetEntityMotionPacket",      // velocity (knockback, fall-damage reset, ...)
            "ClientboundUpdateAttributesPacket",     // movement speed, gravity, jump strength, step height, ...
            "ClientboundSetEntityDataPacket",        // shared flags (sprinting, swimming, gliding), pose
            "ClientboundUpdateMobEffectPacket",      // speed, jump boost, levitation, slow falling, ...
            "ClientboundRemoveMobEffectPacket",
            "ClientboundTeleportEntityPacket",
            "ClientboundEntityPositionSyncPacket");

    /** Packets that always concern the local player. */
    public static final Set<String> ALWAYS = Set.of(
            "ClientboundPlayerPositionPacket",       // server teleport / correction
            "ClientboundPlayerRotationPacket",
            "ClientboundExplodePacket",              // player knockback
            "ClientboundPlayerAbilitiesPacket",      // flying, fly speed
            "ClientboundRespawnPacket");

    private static final Pattern FIRST_ID = Pattern.compile("^[\\w$]+\\[(?:id|entityId)=(-?\\d+)[,\\]]");
    private static final Pattern LOGIN = Pattern.compile("^ClientboundLoginPacket\\[playerId=(-?\\d+)[,\\]]");

    public static boolean isCandidate(String className) {
        return BY_ID.contains(className) || ALWAYS.contains(className);
    }

    /** Entity id of the local player from a {@code ClientboundLoginPacket} summary, or null. */
    public static Integer loginPlayerId(String className, String summary) {
        if (!"ClientboundLoginPacket".equals(className) || summary == null) return null;
        Matcher m = LOGIN.matcher(summary);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    public static boolean targetsPlayer(String className, String summary, Integer playerId) {
        if (ALWAYS.contains(className)) return true;
        if (!BY_ID.contains(className) || playerId == null || summary == null) return false;
        Matcher m = FIRST_ID.matcher(summary);
        return m.find() && Integer.parseInt(m.group(1)) == playerId;
    }
}
