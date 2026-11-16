package me.biquaternions.inputreplay.client.capture;

import com.mojang.blaze3d.platform.InputConstants;
import me.biquaternions.inputreplay.client.io.Bin;
import me.biquaternions.inputreplay.client.io.Records;
import me.biquaternions.inputreplay.client.io.Records.Stream;
import me.biquaternions.inputreplay.client.session.BindingResolver;
import me.biquaternions.inputreplay.client.session.Session;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Receives input events from the KeyboardHandler / MouseHandler mixins (main thread) and writes them
 * to {@code inputs.bin}, tagged with the tick they precede. CURSOR events are coalesced per frame.
 */
public final class InputCapture {
    private InputCapture() {}

    private static boolean inTextInput;
    private static Bin pendingCursor;
    private static long pendingCursorFrame = -1;
    private static long[] pendingCursorStamp;

    private static Session session() {
        Session s = SessionManager.session();
        return s == null || s.ended() ? null : s;
    }

    static boolean isGameWindow(long window) {
        return window == Minecraft.getInstance().getWindow().handle();
    }

    public static void onKey(long window, int action, KeyEvent event) {
        Session s = session();
        if (s == null || !isGameWindow(window)) return;
        flushCursor(s);
        Bin b = new Bin(48).svarint(action).svarint(event.key()).svarint(event.keycode()).svarint(event.modifiers())
                .strs(BindingResolver.mappingNames(InputConstants.getKey(event)));
        s.emit(Stream.INPUTS, Records.KEY, b);
    }

    public static void onTextStart(long window, String text) {
        inTextInput = true;
        Session s = session();
        if (s == null || !isGameWindow(window)) return;
        flushCursor(s);
        s.emit(Stream.INPUTS, Records.TEXT, new Bin(16 + text.length()).str(text));
    }

    public static void onTextEnd() {
        inTextInput = false;
    }

    /** charTyped calls nested in textInput are covered by the TEXT record. */
    public static void onChar(long window, CharacterEvent event) {
        if (inTextInput) return;
        Session s = session();
        if (s == null || !isGameWindow(window)) return;
        flushCursor(s);
        s.emit(Stream.INPUTS, Records.CHAR, new Bin(8).svarint(event.codepoint()));
    }

    public static void onButton(long window, MouseButtonInfo info, int action) {
        Session s = session();
        if (s == null || !isGameWindow(window)) return;
        flushCursor(s);
        Minecraft mc = Minecraft.getInstance();
        Bin b = new Bin(64).svarint(action).svarint(info.button()).svarint(info.modifiers())
                .f64(mc.mouseHandler.xpos()).f64(mc.mouseHandler.ypos())
                .strs(BindingResolver.mappingNames(InputConstants.Type.MOUSE.getOrCreate(info.button())));
        s.emit(Stream.INPUTS, Records.MOUSE_BUTTON, b);
    }

    public static void onScroll(long window, double dx, double dy) {
        Session s = session();
        if (s == null || !isGameWindow(window)) return;
        flushCursor(s);
        s.emit(Stream.INPUTS, Records.SCROLL, new Bin(16).f64(dx).f64(dy));
    }

    /** Recorded only while a screen is open or a button is held; coalesced to the last move per frame. */
    public static void onMove(long window, double x, double y, double dx, double dy) {
        Session s = session();
        if (s == null || !isGameWindow(window)) return;
        Minecraft mc = Minecraft.getInstance();
        boolean relevant = mc.gui.screen() != null || mc.mouseHandler.isLeftPressed()
                || mc.mouseHandler.isRightPressed() || mc.mouseHandler.isMiddlePressed();
        if (!relevant) return;
        if (me.biquaternions.inputreplay.client.replay.InputGate.injecting) {
            // a replayed move: keep each one, like the source recording has them
            flushCursor(s);
            s.emit(Stream.INPUTS, Records.CURSOR, new Bin(32).f64(x).f64(y).f64(dx).f64(dy));
            return;
        }
        long frame = s.clock.frame();
        if (pendingCursor != null && pendingCursorFrame != frame) flushCursor(s);
        if (pendingCursor == null) {
            pendingCursor = new Bin(32);
            pendingCursorStamp = new long[]{s.clock.nextSeq(), s.clock.t(), s.clock.tick(), frame};
            pendingCursorFrame = frame;
        } else {
            pendingCursor.reset();
            pendingCursorStamp[1] = s.clock.t();
        }
        pendingCursor.f64(x).f64(y).f64(dx).f64(dy);
    }

    public static void flushCursor(Session s) {
        if (pendingCursor == null) return;
        long[] st = pendingCursorStamp;
        s.emit(Stream.INPUTS, Records.CURSOR, st[0], st[1], (int) st[2], st[3], pendingCursor);
        pendingCursor = null;
        pendingCursorStamp = null;
        pendingCursorFrame = -1;
    }

    /** The exact arguments of a LocalPlayer.turn call (recorded from turnPlayer or by the replayer). */
    public static void onTurn(double dx, double dy) {
        if (dx == 0.0 && dy == 0.0) return;
        Session s = session();
        if (s == null) return;
        flushCursor(s);
        s.emit(Stream.INPUTS, Records.TURN, new Bin(16).f64(dx).f64(dy));
    }

    public static void reset() {
        inTextInput = false;
        pendingCursor = null;
        pendingCursorStamp = null;
        pendingCursorFrame = -1;
    }
}
