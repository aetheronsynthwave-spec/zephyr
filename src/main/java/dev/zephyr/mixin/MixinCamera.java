package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.movement.Freelook;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freelook: detaches the camera rotation from the player while enabled. Movement and
 * rotation stay separated - the player keeps its real yaw/pitch (movement input stays
 * vanilla-relative), only the *view* is redirected to the free rotation.
 * (1.21.11 Camera.update signature: (World, Entity, boolean, boolean, float).)
 */
@Mixin(Camera.class)
public abstract class MixinCamera {

    @Inject(method = "update", at = @At("RETURN"))
    private void zephyr$freelookCamera(World world, Entity focusedEntity, boolean thirdPerson,
                                       boolean inverseView, float tickDelta, CallbackInfo ci) {
        Freelook freelook = ZephyrClient.modules().get(Freelook.class);
        if (freelook != null && freelook.isEnabled() && !thirdPerson) {
            ((Camera) (Object) this).setRotation(freelook.freeYaw(), freelook.freePitch());
        }
    }
}
