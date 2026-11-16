package me.biquaternions.inputreplay.client.replay;

import com.mojang.blaze3d.platform.InputConstants;
import me.biquaternions.inputreplay.client.io.Bin;
import me.biquaternions.inputreplay.client.io.RecordingReader.InputRec;
import me.biquaternions.inputreplay.client.io.Records;
import me.biquaternions.inputreplay.client.io.Records.Stream;
import me.biquaternions.inputreplay.client.mixin.client.KeyMappingAccessor;
import me.biquaternions.inputreplay.client.mixin.client.MouseHandlerAccessor;
import me.biquaternions.inputreplay.client.session.BindingResolver;
import me.biquaternions.inputreplay.client.session.Session;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Feeds recorded input records into the client. Must be called with {@link InputGate#injecting} set,
 * so the input mixins let the calls through (and record them into the replay run).
 *
 * <ul>
 *   <li>{@link BindingMode#PHYSICAL}: the same calls SDL makes ({@code KeyboardHandler.keyPress},
 *       {@code MouseHandler.onButton}, ...) with the recorded key codes.</li>
 *   <li>{@link BindingMode#BY_NAME}: events that carry mapping names drive those mappings directly
 *       ({@code setDown} + click count) when no screen is open, or are routed to the open screen
 *       through the mapping's <em>current</em> key. Events without mapping names are injected
 *       physically, unless the key is bound to something in the current client.</li>
 * </ul>
 */
public final class InputInjector {
    private static final Logger LOG = LoggerFactory.getLogger("InputReplay/Injector");

    public enum BindingMode { PHYSICAL, BY_NAME }

    private final BindingMode mode;
    private final Session session;
    private final Set<String> warnedMissing = new HashSet<>();
    private final Set<String> warnedShadowed = new HashSet<>();
    public final Set<String> missingMappings = new HashSet<>();

    public InputInjector(BindingMode mode, Session session) {
        this.mode = mode;
        this.session = session;
    }

    public BindingMode mode() {
        return mode;
    }

    public void inject(InputRec r) {
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().handle();
        switch (r.type()) {
            case Records.KEY -> {
                if (mode == BindingMode.BY_NAME && injectKeyByName(mc, window, r)) return;
                trackKey(r.key(), r.action());
                mc.keyboardHandler.keyPress(window, r.action(), new KeyEvent(r.key(), r.scancode(), r.modifiers()));
            }
            case Records.MOUSE_BUTTON -> {
                if (mode == BindingMode.BY_NAME && injectButtonByName(mc, window, r)) return;
                restoreCursor(mc, r.x(), r.y());
                mc.mouseHandler.onButton(window, new MouseButtonInfo(r.button(), r.modifiers()), r.action());
            }
            case Records.TEXT -> mc.keyboardHandler.textInput(window, r.text());
            case Records.CHAR -> mc.keyboardHandler.charTyped(window, new CharacterEvent(r.codepoint()));
            case Records.SCROLL -> mc.mouseHandler.onScroll(window, r.dx(), r.dy());
            case Records.CURSOR -> mc.mouseHandler.onMove(window, r.x(), r.y(), r.dx(), r.dy());
            default -> {
            }
        }
    }

    private static void restoreCursor(Minecraft mc, double x, double y) {
        if (mc.gui.screen() == null) return;
        MouseHandlerAccessor acc = (MouseHandlerAccessor) mc.mouseHandler;
        acc.inputreplay$setXpos(x);
        acc.inputreplay$setYpos(y);
    }

    /** SDL reports a key as down from its press event until its release event. */
    private static void trackKey(int key, int action) {
        if (action == InputConstants.PRESS) VirtualKeyboard.set(key, true);
        else if (action == InputConstants.RELEASE) VirtualKeyboard.set(key, false);
    }

    /** Ctrl / Shift / Alt / GUI (left and right): polled by fixed key code, so tracked by recorded code. */
    static boolean isModifier(int key) {
        return key >= 224 && key <= 231; // SDL scancodes LCTRL..RGUI
    }

    /** Returns true if handled; false to fall back to physical injection. */
    private boolean injectKeyByName(Minecraft mc, long window, InputRec r) {
        List<String> names = r.mappingNames();
        if (isModifier(r.key()) && !names.isEmpty()) trackKey(r.key(), r.action());
        if (names.isEmpty()) {
            return skipIfShadowed(mc, InputConstants.Type.KEYBOARD.getOrCreate(r.key()));
        }
        if (mc.gui.screen() != null) {
            KeyMapping m = firstPresent(names);
            if (m == null) return true;
            InputConstants.Key k = ((KeyMappingAccessor) m).inputreplay$getKey();
            if (k.getType() == InputConstants.Type.MOUSE) {
                mc.mouseHandler.onButton(window, new MouseButtonInfo(k.getValue(), r.modifiers()), r.action());
            } else if (!m.isUnbound()) {
                int scancode = k.getValue() == r.key() ? r.scancode() : 0;
                trackKey(k.getValue(), r.action());
                mc.keyboardHandler.keyPress(window, r.action(), new KeyEvent(k.getValue(), scancode, r.modifiers()));
            } else {
                LOG.warn("{} is unbound in this client; cannot route it to the open screen", m.getName());
                drive(names, r.action());
            }
            return true;
        }
        drive(names, r.action());
        reemit(Records.KEY, r);
        return true;
    }

    private boolean injectButtonByName(Minecraft mc, long window, InputRec r) {
        List<String> names = r.mappingNames();
        if (names.isEmpty()) {
            return skipIfShadowed(mc, InputConstants.Type.MOUSE.getOrCreate(r.button()));
        }
        if (mc.gui.screen() != null) {
            // GUI clicks are positional: inject the recorded button at the recorded cursor position.
            return false;
        }
        drive(names, r.action());
        reemit(Records.MOUSE_BUTTON, r);
        return true;
    }

    /**
     * An event without mapping names in the recording, on a key that is bound in this client, would
     * trigger a mapping the original run never triggered: skip it (only while no screen is open).
     */
    private boolean skipIfShadowed(Minecraft mc, InputConstants.Key key) {
        if (mc.gui.screen() != null) return false;
        List<String> now = BindingResolver.mappingNames(key);
        if (now.isEmpty()) return false;
        if (warnedShadowed.add(key.getName())) {
            LOG.warn("Skipping recorded {} events: unbound in the recording but bound to {} here", key.getName(), now);
        }
        return true;
    }

    /** What {@code KeyMapping.set} / {@code KeyMapping.click} do, addressed by mapping name. */
    private void drive(List<String> names, int action) {
        if (action == InputConstants.REPEAT) return;
        boolean down = action == InputConstants.PRESS;
        for (String name : names) {
            KeyMapping m = KeyMapping.get(name);
            if (m == null) {
                missingMappings.add(name);
                if (warnedMissing.add(name)) LOG.warn("Recorded key mapping {} does not exist in this client; skipped", name);
                continue;
            }
            m.setDown(down);
            InputConstants.Key bound = ((KeyMappingAccessor) m).inputreplay$getKey();
            if (bound.getType() == InputConstants.Type.KEYBOARD) VirtualKeyboard.set(bound.getValue(), down);
            if (down) {
                KeyMappingAccessor acc = (KeyMappingAccessor) m;
                acc.inputreplay$setClickCount(acc.inputreplay$getClickCount() + 1);
            }
        }
    }

    private static KeyMapping firstPresent(List<String> names) {
        for (String n : names) {
            KeyMapping m = KeyMapping.get(n);
            if (m != null) return m;
        }
        return null;
    }

    /** By-name injections bypass the input mixins, so the replay run records the original record. */
    private void reemit(int type, InputRec r) {
        Bin b = new Bin(r.fields().length);
        b.raw(r.fields(), 0, r.fields().length);
        session.emit(Stream.INPUTS, type, b);
    }
}
