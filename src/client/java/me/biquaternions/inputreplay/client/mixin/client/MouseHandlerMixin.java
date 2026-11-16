package me.biquaternions.inputreplay.client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.biquaternions.inputreplay.client.capture.InputCapture;
import me.biquaternions.inputreplay.client.replay.InputGate;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void inputreplay$onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (InputGate.blocking()) {
            ci.cancel();
            return;
        }
        InputCapture.onButton(window, info, action);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void inputreplay$onScroll(long window, double dx, double dy, CallbackInfo ci) {
        if (InputGate.blocking()) {
            ci.cancel();
            return;
        }
        InputCapture.onScroll(window, dx, dy);
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void inputreplay$onMove(long window, double x, double y, double dx, double dy, CallbackInfo ci) {
        if (InputGate.blocking()) {
            ci.cancel();
            return;
        }
        InputCapture.onMove(window, x, y, dx, dy);
    }

    /**
     * The doubles passed to LocalPlayer.turn already include sensitivity, frame time, smoothing and
     * spyglass scaling, so they are what gets recorded (and replayed). During replay the per-frame
     * turn is suppressed; the replayer applies the recorded turns at tick boundaries.
     */
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void inputreplay$turn(LocalPlayer player, double dx, double dy, Operation<Void> original) {
        if (InputGate.blocking()) return;
        InputCapture.onTurn(dx, dy);
        original.call(player, dx, dy);
    }
}
