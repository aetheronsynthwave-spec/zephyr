package dev.zephyr.modules.movement;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Freelook - detached camera view with full movement/rotation separation.
 *
 * <p>While enabled, cursor deltas feed the free rotation instead of the player's look
 * direction (MixinMouse redirect) and the camera renders from the free rotation
 * (MixinCamera). Movement input, sprint, and server-side rotation all remain bound to
 * the player's real yaw/pitch - only the view detaches.
 */
public final class Freelook extends Module {

    private final NumberSetting sensitivity = num("sens", "Free-look sensitivity", 0.1, 3.0, 0.05, 1.0);
    private final BooleanSetting invertPitch = bool("invert", "Invert pitch", false);
    private final BooleanSetting rollOnToggle = bool("restore", "Sync player view on toggle", false);

    private double freeYaw;
    private double freePitch;

    public Freelook() {
        // Left Alt by default: press once to detach the camera, again to snap back.
        super("Freelook", ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_LEFT_ALT);
    }

    @Override
    protected void onEnable() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            freeYaw = player.getYaw();
            freePitch = player.getPitch();
        }
    }

    @Override
    protected void onDisable() {
        if (rollOnToggle.get()) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player != null) {
                player.setYaw((float) freeYaw);
                player.setPitch((float) freePitch);
            }
        }
    }

    /** Called from the mouse mixin with raw cursor deltas. */
    public void feed(double dx, double dy) {
        double scale = 0.15 * sensitivity.get();
        freeYaw = MathHelper.wrapDegrees(freeYaw + dx * scale);
        double pitchStep = dy * scale * (invertPitch.get() ? -1 : 1);
        freePitch = MathHelper.clamp(freePitch + pitchStep, -90.0, 90.0);
    }

    public float freeYaw() {
        return (float) freeYaw;
    }

    public float freePitch() {
        return (float) freePitch;
    }

    @Override
    public String hudLabel() {
        return String.format("%s · yaw %.0f pitch %.0f", name, freeYaw, freePitch);
    }
}
