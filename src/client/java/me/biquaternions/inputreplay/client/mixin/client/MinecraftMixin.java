package me.biquaternions.inputreplay.client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.biquaternions.inputreplay.client.replay.Replayer;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void inputreplay$frame(boolean advanceGameTime, CallbackInfo ci) {
        SessionManager.onFrame();
    }

    /**
     * Packet-sync replay: skip a client tick (the frame still renders) while a player packet that the
     * recording had applied by this tick has not arrived yet. Wrapping the call site rather than
     * cancelling tick() at HEAD keeps Fabric's tick events and the session clock consistent.
     */
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
    private void inputreplay$tick(Minecraft mc, Operation<Void> original) {
        if (Replayer.shouldStallTick()) return;
        original.call(mc);
    }
}
