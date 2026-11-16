package me.biquaternions.inputreplay.client.gui;

import me.biquaternions.inputreplay.client.Config;
import me.biquaternions.inputreplay.client.replay.Replayer;
import me.biquaternions.inputreplay.client.session.Mode;
import me.biquaternions.inputreplay.client.session.Session;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Top-left status line: {@code REC tick 1234 in 5678 pkts out 2345 pkts 12.3 MB}. */
public final class SessionHud {
    private SessionHud() {}

    public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!Config.get().hudEnabled) return;
        Session s = SessionManager.session();
        if (s == null || s.ended()) return;
        Minecraft mc = Minecraft.getInstance();
        List<String> lines = new ArrayList<>();
        String tick = s.clock.anchored() ? String.valueOf(s.clock.anchored(s.lastTick)) : "-- (" + s.lastTick + " raw)";
        String tag = "REC";
        lines.add(String.format("%s  tick %s  in %d pkts  out %d pkts  %.1f MB%s", tag, tick,
                s.inPackets.get(), s.outPackets.get(), s.writer.writtenBytes() / (1024.0 * 1024.0),
                s.writer.overflowed() ? "  OVERFLOW" : ""));
        Replayer r = Replayer.active();
        if (r != null) lines.addAll(r.hudLines());
        int y = 2;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int w = mc.font.width(line);
            g.fill(1, y - 1, 3 + w, y + 9, 0x90000000);
            g.text(mc.font, line, 2, y, i > 0 || s.mode == Mode.REPLAY ? 0xFF55FFFF : 0xFFFF5555, false);
            y += 11;
        }
    }
}
