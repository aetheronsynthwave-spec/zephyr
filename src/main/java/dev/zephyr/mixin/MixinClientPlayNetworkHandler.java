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
}
