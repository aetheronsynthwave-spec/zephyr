package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.utility.SoundDetector;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinClientPlayNetworkHandler {

    @Inject(method = "onPlaySound", at = @At("HEAD"), require = 0)
    private void zephyr$sound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        SoundDetector sd = ZephyrClient.modules() == null ? null : ZephyrClient.modules().get(SoundDetector.class);
        if (sd == null) {
            return;
        }
        String path = packet.getSound().getKey().map(k -> k.getValue().getPath()).orElse("");
        sd.onSound(path, packet.getX(), packet.getY(), packet.getZ());
    }

    /**
     * JumpReset: the knockback itself. Packet handlers run once on the network thread
     * (which only re-schedules) and once on the client thread - only the latter counts.
     */
    @Inject(method = "onEntityVelocityUpdate", at = @At("TAIL"), require = 0)
    private void zephyr$knockback(net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket packet,
                                  CallbackInfo ci) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (!mc.isOnThread() || mc.player == null || packet.getEntityId() != mc.player.getId()
                || ZephyrClient.modules() == null) {
            return;
        }
        var jr = ZephyrClient.modules().get(dev.zephyr.modules.combat.JumpReset.class);
        if (jr != null) {
            jr.onKnockback(packet.getVelocity());
        }
    }
}
