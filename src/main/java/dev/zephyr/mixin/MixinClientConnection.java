package dev.zephyr.mixin;

import dev.zephyr.core.event.ZephyrEvents;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancellable outbound-packet hook: the gate used by packet-level guards and module filters. */
@Mixin(ClientConnection.class)
public abstract class MixinClientConnection {

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void zephyr$onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (!ZephyrEvents.PACKET_SEND.hasListeners()) {
            return; // runs for every outbound packet: no allocation when unused
        }
        ZephyrEvents.PacketSend event = new ZephyrEvents.PacketSend(packet);
        ZephyrEvents.PACKET_SEND.fire(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}
