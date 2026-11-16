package me.biquaternions.inputreplay.client.mixin.client;

import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Arms a mode (or opens the prompt) before connecting, so handshake/login/configuration are captured. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void inputreplay$beforeConnect(Screen parent, Minecraft mc, ServerAddress address, ServerData data,
                                                 boolean quickPlay, TransferState transfer, CallbackInfo ci) {
        if (SessionManager.onStartConnecting(parent, mc, address, data, quickPlay, transfer)) ci.cancel();
    }
}
