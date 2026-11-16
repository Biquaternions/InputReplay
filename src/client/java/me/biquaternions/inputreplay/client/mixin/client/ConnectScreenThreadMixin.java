package me.biquaternions.inputreplay.client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.network.ClientboundPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Identifies the Connection created by ConnectScreen (as opposed to server-list pings), right
 * before the handshake is sent, i.e. before any packet of the session flows.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class ConnectScreenThreadMixin {
    @WrapOperation(method = "run", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;initiateServerboundPlayConnection(Ljava/lang/String;ILnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ClientboundPacketListener;Z)V"))
    private void inputreplay$onConnection(Connection connection, String host, int port, ProtocolInfo<?> serverbound,
                                          ProtocolInfo<?> clientbound, ClientboundPacketListener listener, boolean transfer,
                                          Operation<Void> original) {
        SessionManager.onConnectionCreated(connection);
        original.call(connection, host, port, serverbound, clientbound, listener, transfer);
    }
}
