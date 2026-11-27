package dev.zephyr.core.rotation;

import dev.zephyr.core.timing.RandomController;
import dev.zephyr.core.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Central rotation authority.
 *
 * <p>Design:
 * <ul>
 *   <li>Only one active request at a time; higher priority wins, equal priority replaces.</li>
 *   <li>Advancement is bounded: per-tick angular step is sampled in [minSpeed, maxSpeed]
 *       (gaussian, center-weighted) plus a micro-jitter roll of at most {@code jitterDeg}.</li>
 *   <li>Silent mode: the visible camera never moves. The mixin layer calls
 *       {@link #beforeMovementPackets}/{@link #afterMovementPackets} around
 *       {@code ClientPlayerEntity#sendMovementPackets} so the packets leaving the client
 *       carry the server-side rotation while the local player keeps its real view.</li>
 *   <li>Non-silent mode additionally drives the visible camera along the same smoothed path.</li>
 * </ul>
 */
public final class RotationManager {

    private RotationManager() {
    }

    private static RotationRequest active;
    private static int ageTicks;

    /** Server-side (packet) rotation. */
    private static double serverYaw;
    private static double serverPitch;
    /** Whether server rotation has ever been seeded from the player's real view. */
    private static boolean seeded;

    public static void request(RotationRequest request) {
        if (active == null || request.priority >= active.priority || !active.validity.getAsBoolean()) {
            active = request;
            ageTicks = 0;
        }
    }

    /** Clears the active request (used when a module disables itself mid-path). */
    public static void clear(int minPriority) {
        if (active != null && active.priority <= minPriority) {
            active = null;
        }
    }

    public static void clearAll() {
        active = null;
    }

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            active = null;
            seeded = false;
            return;
        }
        if (!seeded) {
            serverYaw = player.getYaw();
            serverPitch = player.getPitch();
            seeded = true;
        }
        if (active == null || !active.validity.getAsBoolean() || ageTicks >= active.maxTicks) {
            active = null;
            return;
        }
        ageTicks++;

        double step = RandomController.uniform(active.minSpeedDeg, active.maxSpeedDeg)
                + RandomController.uniform(-active.jitterDeg, active.jitterDeg);
        step = Math.abs(step);
        serverYaw = MathHelper.wrapDegrees(serverYaw + MathHelper.clamp(
                MathUtil.angleDelta(serverYaw, active.targetYaw), -step, step));
        double pitchStep = MathHelper.clamp(MathUtil.angleDelta(serverPitch, active.targetPitch), -step, step);
        serverPitch = MathHelper.clamp(serverPitch + pitchStep, -90.0, 90.0);

        if (!active.silent) {
            // Visible camera follows the identical bounded path (no snap).
            player.setYaw((float) serverYaw);
            player.setPitch((float) serverPitch);
        }

        // Request completes when fully reached for a couple of ticks.
        if (Math.abs(MathUtil.angleDelta(serverYaw, active.targetYaw)) < 0.05
                && Math.abs(serverPitch - active.targetPitch) < 0.05
                && ageTicks >= 2) {
            active = null;
        }
    }

    // ------------------------------------------------------------------
    // Mixin hook region (movement packets)
    // ------------------------------------------------------------------

    private static double savedYaw;
    private static double savedPitch;
    private static boolean swapping;

    /**
     * Executes {@code action} with the player's live rotation temporarily set to the
     * server rotation. Used so interaction packets (which embed yaw/pitch since 1.21.2)
     * carry the aimed rotation while a silent request is active.
     */
    public static <T> T withServerRotation(java.util.function.Supplier<T> action) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || active == null || !active.silent) {
            return action.get();
        }
        double sy = player.getYaw();
        double sp = player.getPitch();
        player.setYaw((float) serverYaw);
        player.setPitch((float) serverPitch);
        try {
            return action.get();
        } finally {
            player.setYaw((float) sy);
            player.setPitch((float) sp);
        }
    }

    /**
     * Called by the mixin at ClientPlayerEntity#sendMovementPackets HEAD.
     * Swaps the player's live yaw/pitch to the server rotation so outgoing packets
     * carry it. Not a full rotation packet substitute - it rides the vanilla writer.
     */
    public static void beforeMovementPackets(ClientPlayerEntity player) {
        if (active != null && active.silent && !swapping) {
            swapping = true;
            savedYaw = player.getYaw();
            savedPitch = player.getPitch();
            player.setYaw((float) serverYaw);
            player.setPitch((float) serverPitch);
        }
    }

    /** Called by the mixin at RETURN; restores the real client view. */
    public static void afterMovementPackets(ClientPlayerEntity player) {
        if (swapping) {
            swapping = false;
            player.setYaw((float) savedYaw);
            player.setPitch((float) savedPitch);
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Required yaw/pitch from the local eyes to a world point. */
    public static double[] requiredAngles(Vec3d point) {
        ClientPlayerEntity player = net.minecraft.client.MinecraftClient.getInstance().player;
        if (player == null) {
            return new double[]{0, 0};
        }
        Vec3d eyes = player.getEyePos();
        return new double[]{MathUtil.yawTo(eyes, point), MathUtil.pitchTo(eyes, point)};
    }

    /** @return true if the server rotation is within {@code toleranceDeg} of the point's angles. */
    public static boolean isAimedAt(Vec3d point, double toleranceDeg) {
        if (active != null && active.silent && !hasConverged(point, toleranceDeg)) {
            return false;
        }
        double[] req = requiredAngles(point);
        double yawDelta = Math.abs(MathUtil.angleDelta(currentYaw(), req[0]));
        double pitchDelta = Math.abs(currentPitch() - req[1]);
        return yawDelta <= toleranceDeg && pitchDelta <= toleranceDeg;
    }

    private static boolean hasConverged(Vec3d point, double toleranceDeg) {
        double[] req = requiredAngles(point);
        return Math.abs(MathUtil.angleDelta(serverYaw, req[0])) <= toleranceDeg
                && Math.abs(serverPitch - req[1]) <= toleranceDeg;
    }

    public static double currentYaw() {
        return serverYaw;
    }

    public static double currentPitch() {
        return serverPitch;
    }

    public static boolean isSilentActive() {
        return active != null && active.silent;
    }

    /** Reset on world change so the server rotation re-seeds from the fresh player. */
    public static void invalidate() {
        seeded = false;
        active = null;
    }
}
