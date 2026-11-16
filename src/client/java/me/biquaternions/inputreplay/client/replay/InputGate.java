package me.biquaternions.inputreplay.client.replay;

import me.biquaternions.inputreplay.client.Config;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Decides whether a hardware input event reaches the game. While a replay is active, real input is
 * blocked; only the replayer's own calls (made with {@link #injecting} set) and the abort key pass.
 * Main thread only.
 */
public final class InputGate {
    private InputGate() {}

    /** Set by the replayer around its calls into KeyboardHandler / MouseHandler. */
    public static boolean injecting;
    /** True while a replay drives the player. */
    public static volatile boolean replayActive;
    /** Invoked when the abort key is pressed during a replay. */
    public static Runnable onAbort = () -> {};

    public static boolean blocking() {
        return replayActive && !injecting;
    }

    /** For keyPress: returns true if the event must be cancelled. Handles the abort key. */
    public static boolean blockKey(int action, int key) {
        if (!blocking()) return false;
        if (key == Config.get().abortKey && action == InputConstants.PRESS) onAbort.run();
        return true;
    }
}
