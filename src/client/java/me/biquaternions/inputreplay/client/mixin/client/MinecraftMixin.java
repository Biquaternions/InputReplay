package me.biquaternions.inputreplay.client.mixin.client;

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
}
