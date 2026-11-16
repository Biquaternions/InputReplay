package me.biquaternions.inputreplay.client.mixin.client;

import me.biquaternions.inputreplay.client.capture.PacketCapture;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stamps the main-thread tick at which each inbound packet is applied. */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class PacketProcessorMixin {
    @Shadow
    @Final
    private Packet<?> packet;

    @Inject(method = "handle", at = @At("HEAD"))
    private void inputreplay$handle(CallbackInfo ci) {
        PacketCapture.onApply(packet);
    }
}
