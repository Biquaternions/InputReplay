package me.biquaternions.inputreplay.client.mixin.client;

import me.biquaternions.inputreplay.client.capture.InputCapture;
import me.biquaternions.inputreplay.client.replay.InputGate;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void inputreplay$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (InputGate.blockKey(action, event.key())) {
            ci.cancel();
            return;
        }
        InputCapture.onKey(window, action, event);
    }

    @Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
    private void inputreplay$textInput(long window, String text, CallbackInfo ci) {
        if (InputGate.blocking()) {
            ci.cancel();
            return;
        }
        InputCapture.onTextStart(window, text);
    }

    @Inject(method = "textInput", at = @At("RETURN"))
    private void inputreplay$textInputEnd(long window, String text, CallbackInfo ci) {
        InputCapture.onTextEnd();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void inputreplay$charTyped(long window, CharacterEvent event, CallbackInfo ci) {
        if (InputGate.blocking()) {
            ci.cancel();
            return;
        }
        InputCapture.onChar(window, event);
    }
}
