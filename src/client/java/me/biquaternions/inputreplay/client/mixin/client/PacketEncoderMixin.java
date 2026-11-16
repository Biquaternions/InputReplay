package me.biquaternions.inputreplay.client.mixin.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import me.biquaternions.inputreplay.client.capture.PacketCapture;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Outbound capture at the encoder: uncompressed, unframed bytes (varint id + body) per packet. */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {
    @Shadow
    @Final
    private ProtocolInfo<?> protocolInfo;

    @Unique
    private int inputreplay$start = -1;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"))
    private void inputreplay$head(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        var s = SessionManager.session();
        inputreplay$start = (s != null && s.channel == ctx.channel()) ? out.writerIndex() : -1;
    }

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"))
    private void inputreplay$return(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        int start = inputreplay$start;
        inputreplay$start = -1;
        if (start < 0) return;
        byte[] b = new byte[out.writerIndex() - start];
        out.getBytes(start, b);
        PacketCapture.onEncoded(ctx.channel(), protocolInfo.id().id(), packet, b);
    }
}
