package me.biquaternions.inputreplay.client.capture;

import io.netty.channel.Channel;
import me.biquaternions.inputreplay.client.io.Bin;
import me.biquaternions.inputreplay.client.io.Records;
import me.biquaternions.inputreplay.client.io.Records.Stream;
import me.biquaternions.inputreplay.client.session.Session;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Packet capture, fed by the PacketDecoder / PacketEncoder / Connection / PacketProcessor mixins.
 * <ul>
 *   <li>Inbound: {@code PACKET_IN} at decode time (Netty thread: raw bytes + decoded object), then
 *       {@code PACKET_APPLY} with the same {@code pid} when the main thread handles it. Packets
 *       handled directly on the Netty thread (login phase, keep-alive…) have no APPLY record.</li>
 *   <li>Outbound: stamped at {@code Connection.send} (main thread, exact tick), written as
 *       {@code PACKET_OUT} once encoded (Netty thread), matched by object identity.</li>
 * </ul>
 */
public final class PacketCapture {
    private PacketCapture() {}

    private static final AtomicLong PID = new AtomicLong();
    private static final Object LOCK = new Object();
    /** decoded packet → pending pids (+ raw tick of receipt), for apply stamping. */
    private static final Map<Object, ArrayDeque<long[]>> PENDING_APPLY = new IdentityHashMap<>();
    /** sent packet → send-time stamps {seq, t, tick, frame}. */
    private static final Map<Object, ArrayDeque<long[]>> PENDING_SEND = new IdentityHashMap<>();
    private static boolean inBundle;
    private static int bundleIndex;

    public static void reset() {
        PID.set(0);
        synchronized (LOCK) {
            PENDING_APPLY.clear();
            PENDING_SEND.clear();
        }
        inBundle = false;
        bundleIndex = 0;
    }

    private static Session sessionFor(Channel ch) {
        Session s = SessionManager.session();
        if (s == null || s.ended() || ch == null || s.channel != ch) return null;
        return s;
    }

    /** Netty thread, from PacketDecoder.decode RETURN. */
    public static void onDecoded(Channel ch, String phase, byte[] raw, Packet<?> packet) {
        Session s = sessionFor(ch);
        if (s == null) return;
        String cls = PacketSummarizer.className(packet);
        int bIdx = -1;
        if (packet instanceof BundleDelimiterPacket<?>) {
            inBundle = !inBundle;
            bundleIndex = 0;
        } else if (inBundle) {
            bIdx = bundleIndex++;
        }
        if (cls.contains("Disconnect") && cls.startsWith("Clientbound")) s.serverDisconnectSeen = true;

        long pid = PID.getAndIncrement();
        if (!(packet instanceof BundleDelimiterPacket<?>)) {
            synchronized (LOCK) {
                PENDING_APPLY.computeIfAbsent(packet, k -> new ArrayDeque<>(1)).add(new long[]{pid, s.clock.tick()});
            }
        }
        PacketSummarizer.Policy policy = PacketSummarizer.policy(cls);
        Bin b = new Bin(raw.length + 128);
        b.uvarint(pid).str(phase).svarint(PacketSummarizer.packetId(raw)).str(cls).svarint(bIdx).uvarint(raw.length);
        writeContent(b, policy, packet, raw);
        s.inPackets.incrementAndGet();
        s.emit(Stream.PACKETS_IN, Records.PACKET_IN, b);
    }

    /** Main thread, from PacketProcessor.ListenerAndPacket.handle HEAD. */
    public static void onApply(Packet<?> packet) {
        Session s = SessionManager.session();
        if (s == null || s.ended()) return;
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) stampApply(s, sub);
        } else {
            stampApply(s, packet);
        }
    }

    private static void stampApply(Session s, Packet<?> packet) {
        long[] entry;
        synchronized (LOCK) {
            ArrayDeque<long[]> q = PENDING_APPLY.get(packet);
            if (q == null) return;
            entry = q.poll();
            if (q.isEmpty()) PENDING_APPLY.remove(packet);
        }
        if (entry != null) s.emit(Stream.PACKETS_IN, Records.PACKET_APPLY, new Bin(8).uvarint(entry[0]));
    }

    /** Main thread (usually), from Connection.send HEAD. */
    public static void onSend(Channel ch, Packet<?> packet) {
        Session s = sessionFor(ch);
        if (s == null) return;
        long[] stamp = {s.clock.nextSeq(), s.clock.t(), s.clock.tick(), s.clock.frame()};
        synchronized (LOCK) {
            PENDING_SEND.computeIfAbsent(packet, k -> new ArrayDeque<>(1)).add(stamp);
        }
    }

    /** Netty thread, from PacketEncoder.encode RETURN. */
    public static void onEncoded(Channel ch, String phase, Packet<?> packet, byte[] bytes) {
        Session s = sessionFor(ch);
        if (s == null) return;
        long[] stamp = null;
        synchronized (LOCK) {
            ArrayDeque<long[]> q = PENDING_SEND.get(packet);
            if (q != null) {
                stamp = q.poll();
                if (q.isEmpty()) PENDING_SEND.remove(packet);
            }
        }
        boolean stamped = stamp != null;
        if (!stamped) stamp = new long[]{s.clock.nextSeq(), s.clock.t(), s.clock.tick(), s.clock.frame()};
        String cls = PacketSummarizer.className(packet);
        Bin b = new Bin(bytes.length + 128);
        b.str(phase).svarint(PacketSummarizer.packetId(bytes)).str(cls).uvarint(bytes.length).bool(stamped);
        writeContent(b, PacketSummarizer.policy(cls), packet, bytes);
        s.outPackets.incrementAndGet();
        s.emit(Stream.PACKETS_OUT, Records.PACKET_OUT, stamp[0], stamp[1], (int) stamp[2], stamp[3], b);
    }

    private static void writeContent(Bin b, PacketSummarizer.Policy policy, Packet<?> packet, byte[] bytes) {
        switch (policy) {
            case FULL -> b.u8(Records.PAYLOAD_FULL).str(PacketSummarizer.summary(packet)).bytes(bytes);
            case SUMMARY -> b.u8(Records.PAYLOAD_NONE).str(PacketSummarizer.summary(packet)).bytes(null);
            case SKIP -> b.u8(Records.PAYLOAD_NONE).str("").bytes(null);
        }
    }

    /** Drops stale identity entries (packets handled off-thread or never sent). Main thread, per tick. */
    public static void cleanup(int currentRawTick) {
        synchronized (LOCK) {
            for (Iterator<ArrayDeque<long[]>> it = PENDING_APPLY.values().iterator(); it.hasNext(); ) {
                ArrayDeque<long[]> q = it.next();
                q.removeIf(e -> currentRawTick - e[1] > 1200);
                if (q.isEmpty()) it.remove();
            }
            for (Iterator<ArrayDeque<long[]>> it = PENDING_SEND.values().iterator(); it.hasNext(); ) {
                ArrayDeque<long[]> q = it.next();
                q.removeIf(e -> currentRawTick - e[2] > 1200);
                if (q.isEmpty()) it.remove();
            }
        }
    }
}
