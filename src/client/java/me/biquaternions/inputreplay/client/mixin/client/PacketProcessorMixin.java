package me.biquaternions.inputreplay.client.mixin.client;

import me.biquaternions.inputreplay.client.capture.PacketCapture;
import me.biquaternions.inputreplay.client.replay.Replayer;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stamps the main-thread tick at which each inbound packet is applied, and lets packet-sync replay
 * hold a player packet until the tick it was applied at in the recording (handle() is re-run then).
 */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class PacketProcessorMixin {
    @Shadow
    @Final
    private Packet<?> packet;

    @Shadow
    public abstract void handle();

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void inputreplay$handle(CallbackInfo ci) {
        if (Replayer.syncOnHandle(packet, this::handle)) {
            ci.cancel();
            return;
        }
        PacketCapture.onApply(packet);
    }
}
