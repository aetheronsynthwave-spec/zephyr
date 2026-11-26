package dev.zephyr.modules.movement;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/**
 * Freecam - camera-only flight (ported from Donut Client, MIT). Never writes a player
 * transform, abilities, gamemode or movement packet: the player's input is replaced by a
 * neutral one while the camera (MixinCamera) flies on its own position/rotation.
 */
public final class Freecam extends Module {

    private final NumberSetting speed = num("speed", "Speed", 0.1, 5.0, 0.1, 1.0);
    private final NumberSetting boost = num("boost", "Sprint boost", 1.0, 10.0, 0.5, 3.0);

    private static final Input NEUTRAL = new Input() {
        @Override
        public void tick() {
            this.playerInput = PlayerInput.DEFAULT;
            this.movementVector = Vec2f.ZERO;
        }

        @Override
        public void jump() {
        }
    };

    private ClientPlayerEntity player;
    private Input saved;
    private Vec3d pos = Vec3d.ZERO;
    private Vec3d prev = Vec3d.ZERO;
    private float yaw, pitch;

    public Freecam() {
        super("Freecam", ModuleCategory.MOVEMENT, 0);
    }

    @Override
    protected void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            throw new IllegalStateException("no player");
        }
        player = mc.player;
        saved = player.input;
        NEUTRAL.tick();
        player.input = NEUTRAL;
        pos = prev = player.getEyePos();
        yaw = player.getYaw();
        pitch = player.getPitch();
        release(mc);
    }

    @Override
    protected void onDisable() {
        if (player != null && player.input == NEUTRAL && saved != null) {
            player.input = saved;
        }
        release(MinecraftClient.getInstance());
        player = null;
        saved = null;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.player != player) { // world change / respawn
            setEnabled(false);
            return;
        }
        release(mc);
        prev = pos;
        if (mc.currentScreen != null) {
            return;
        }
        GameOptions o = mc.options;
        double fwd = axis(o.forwardKey.isPressed(), o.backKey.isPressed());
        double str = axis(o.rightKey.isPressed(), o.leftKey.isPressed());
        double vert = axis(o.jumpKey.isPressed(), o.sneakKey.isPressed());
        Vec3d look = Vec3d.fromPolar(pitch, yaw);
        double r = Math.toRadians(yaw);
        Vec3d motion = look.multiply(fwd).add(-Math.cos(r) * str, vert, -Math.sin(r) * str);
        if (motion.lengthSquared() > 1.0e-8) {
            double sp = speed.get() * (o.sprintKey.isPressed() ? boost.get() : 1.0);
            pos = pos.add(motion.normalize().multiply(sp));
        }
    }

    private static double axis(boolean pos, boolean neg) {
        return (pos ? 1 : 0) - (neg ? 1 : 0);
    }

    private static void release(MinecraftClient mc) {
        GameOptions o = mc.options;
        for (var k : new net.minecraft.client.option.KeyBinding[]{o.attackKey, o.useKey, o.pickItemKey,
                o.dropKey, o.swapHandsKey}) {
            k.setPressed(false);
            while (k.wasPressed()) { /* drain */ }
        }
    }

    public void turn(double dx, double dy) {
        yaw = MathHelper.wrapDegrees(yaw + (float) (dx * 0.15));
        pitch = MathHelper.clamp(pitch + (float) (dy * 0.15), -90f, 90f);
    }

    public Vec3d interpolated(float t) {
        return prev.lerp(pos, MathHelper.clamp(t, 0f, 1f));
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }
}
