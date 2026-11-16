package me.biquaternions.inputreplay.client.mixin.client;

import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Shadow
    public abstract Screen screen();

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void inputreplay$setScreen(Screen next, CallbackInfo ci) {
        SessionManager.onScreenChange(screen(), next);
    }
}
