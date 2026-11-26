package dev.zephyr.mixin;

import dev.zephyr.ZephyrClient;
import dev.zephyr.modules.movement.Freecam;
import dev.zephyr.modules.movement.Freelook;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Freelook: detaches the camera rotation from the player while enabled. Movement and
 * rotation stay separated - the player keeps its real yaw/pitch (movement input stays
 * vanilla-relative), only the *view* is redirected to the free rotation.
 *
 * <p>Two independent hooks (belt and suspenders - with defaultRequire 0 a single
 * signature drift would previously fail silently and Freelook appeared dead):
 * <ol>
 *   <li>{@code @ModifyArgs} on the {@code setRotation(yaw, pitch)} call INSIDE
 *       {@code Camera#update} - the vanilla body then computes its direction vector,
 *       quaternion and planes from the free angles (the same proven pattern Corz
 *       uses on 1.21.11).</li>
 *   <li>{@code @Inject} at RETURN as a secondary application for any early-return
 *       path that skips the internal call.</li>
 * </ol>
 * (1.21.11 Camera.update signature: (World, Entity, boolean, boolean, float).)
 */
@Mixin(Camera.class)
public abstract class MixinCamera {

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(net.minecraft.util.math.Vec3d pos);

    @Shadow
    private boolean thirdPerson;

    /** Freecam: fly the camera on its own transform (own model renders: detached). */
    @Inject(method = "update", at = @At("RETURN"), require = 0)
    private void zephyr$freecam(World world, Entity focusedEntity, boolean thirdPerson,
                                boolean inverseView, float tickDelta, CallbackInfo ci) {
        Freecam fc = ZephyrClient.modules().get(Freecam.class);
        if (fc != null && fc.isEnabled()) {
            setPos(fc.interpolated(tickDelta));
            setRotation(fc.yaw(), fc.pitch());
            this.thirdPerson = true;
        }
    }

    /** Primary hook: rewrite the rotation arguments before vanilla consumes them. */
    @ModifyArgs(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"))
    private void zephyr$freelookArgs(Args args) {
        Freelook freelook = ZephyrClient.modules().get(Freelook.class);
        if (freelook == null || !freelook.isEnabled()) {
            return;
        }
        Entity focused = ((Camera) (Object) this).getFocusedEntity();
        if (focused != MinecraftClient.getInstance().player) {
            return;
        }
        boolean front = MinecraftClient.getInstance().options.getPerspective()
                == net.minecraft.client.option.Perspective.THIRD_PERSON_FRONT;
        // Front view: vanilla turns the camera around (yaw + 180, pitch mirrored).
        args.set(0, front ? freelook.freeYaw() + 180f : freelook.freeYaw());
        args.set(1, front ? -freelook.freePitch() : freelook.freePitch());
    }

    /** Secondary hook: re-apply at RETURN in case the internal call was bypassed. */
    @Inject(method = "update", at = @At("RETURN"))
    private void zephyr$freelookCamera(World world, Entity focusedEntity, boolean thirdPerson,
                                       boolean inverseView, float tickDelta, CallbackInfo ci) {
        Freelook freelook = ZephyrClient.modules().get(Freelook.class);
        if (freelook != null && freelook.isEnabled() && !thirdPerson) {
            setRotation(freelook.freeYaw(), freelook.freePitch());
        }
    }
}
