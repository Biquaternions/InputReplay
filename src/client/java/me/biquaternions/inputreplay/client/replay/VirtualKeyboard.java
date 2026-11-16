package me.biquaternions.inputreplay.client.replay;

import java.util.BitSet;

/**
 * The keyboard as the replay sees it. In 26.3 several code paths poll the hardware directly through
 * {@code InputConstants.isKeyDown} (SDL_GetKeyboardState) instead of using input events:
 * {@code Minecraft.hasControlDown/hasShiftDown/hasAltDown} (drop whole stack, pick block with data,
 * ...), and {@code KeyMapping.setAll()} (run on mouse grab / focus regain, which re-reads every
 * mapping from the hardware). While a replay is active those calls are answered from this state,
 * which follows the injected key events, instead of the real keyboard.
 * Main thread only (reads from other threads see a consistent-enough snapshot).
 */
public final class VirtualKeyboard {
    private VirtualKeyboard() {}

    private static final BitSet DOWN = new BitSet(512);

    public static synchronized void set(int key, boolean down) {
        if (key < 0) return;
        DOWN.set(key, down);
    }

    public static synchronized boolean isDown(int key) {
        return key >= 0 && DOWN.get(key);
    }

    public static synchronized void clear() {
        DOWN.clear();
    }
}
