package me.biquaternions.inputreplay.client.mixin.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import me.biquaternions.inputreplay.client.capture.PacketCapture;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow
    private Channel channel;

    @Inject(method = "channelActive", at = @At("TAIL"))
    private void inputreplay$channelActive(ChannelHandlerContext ctx, CallbackInfo ci) {
        SessionManager.onChannelActive((Connection) (Object) this, this.channel);
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void inputreplay$channelInactive(ChannelHandlerContext ctx, CallbackInfo ci) {
        SessionManager.onChannelInactive((Connection) (Object) this);
    }

    /** Exact main-thread tick of every outbound packet; bytes are added when encoded. */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void inputreplay$send(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (SessionManager.isSessionConnection((Connection) (Object) this)) PacketCapture.onSend(this.channel, packet);
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void inputreplay$disconnect(DisconnectionDetails details, CallbackInfo ci) {
        SessionManager.onLocalDisconnect((Connection) (Object) this, details.reason().getString());
    }
}
