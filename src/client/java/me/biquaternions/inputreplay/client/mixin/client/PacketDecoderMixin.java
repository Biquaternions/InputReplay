package me.biquaternions.inputreplay.client.mixin.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import me.biquaternions.inputreplay.client.capture.PacketCapture;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Inbound capture at the decoder: the input ByteBuf is one decompressed frame (varint id + body),
 * the output is the decoded packet, so bytes and object pair up exactly (before bundling).
 */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {
    @Shadow
    @Final
    private ProtocolInfo<?> protocolInfo;

    @Unique
    private byte[] inputreplay$raw;

    @Inject(method = "decode", at = @At("HEAD"))
    private void inputreplay$head(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        var s = SessionManager.session();
        if (s == null || s.channel != ctx.channel()) {
            inputreplay$raw = null;
            return;
        }
        byte[] b = new byte[in.readableBytes()];
        in.getBytes(in.readerIndex(), b);
        inputreplay$raw = b;
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void inputreplay$return(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        byte[] raw = inputreplay$raw;
        inputreplay$raw = null;
        if (raw == null || out.isEmpty()) return;
        if (out.get(out.size() - 1) instanceof Packet<?> packet) {
            PacketCapture.onDecoded(ctx.channel(), protocolInfo.id().id(), raw, packet);
        }
    }
}
