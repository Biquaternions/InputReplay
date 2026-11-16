package me.biquaternions.inputreplay.client.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.biquaternions.inputreplay.client.replay.InputGate;
import me.biquaternions.inputreplay.client.replay.VirtualKeyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** During a replay, keyboard polling sees the replayed keys instead of the hardware (see VirtualKeyboard). */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void inputreplay$isKeyDown(int key, CallbackInfoReturnable<Boolean> cir) {
        if (InputGate.replayActive) cir.setReturnValue(VirtualKeyboard.isDown(key));
    }
}
