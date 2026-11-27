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
        if (!Double.isFinite(request.targetYaw) || !Double.isFinite(request.targetPitch)) {
            return; // a NaN angle would stick in the server rotation and the camera
        }
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
        // Finish whatever part of last tick's visible turn the frames did not apply yet.
        flushFrameTurn(player);
        if (!seeded) {
            serverYaw = player.getYaw();
            serverPitch = player.getPitch();
            seeded = true;
        }
        // Unless a silent request owns the packet rotation, the server sees the real view.
        // Re-sync every tick so a new request starts from where the player is actually
        // looking (the old code kept a stale angle and snapped the camera back to it).
        if (active == null || !active.silent) {
            serverYaw = player.getYaw();
            serverPitch = player.getPitch();
        }
        if (active == null || !active.validity.getAsBoolean() || ageTicks >= active.maxTicks) {
            active = null;
            return;
        }
        ageTicks++;

        double yawErr = MathUtil.angleDelta(serverYaw, active.targetYaw);
        double pitchErr = MathUtil.angleDelta(serverPitch, active.targetPitch);
        double dist = Math.hypot(yawErr, pitchErr);
        double step = Math.abs(RandomController.uniform(active.minSpeedDeg, active.maxSpeedDeg)
                + RandomController.uniform(-active.jitterDeg, active.jitterDeg));
        // Ease-out: never cover more than ~55% of the remaining error in one tick, so the
        // aim decelerates into the target instead of slamming onto it at full speed.
        step = Math.min(step, Math.max(dist * 0.55, Math.min(dist, 0.6)));
        // Move along the straight line to the target (yaw and pitch arrive together).
        double scale = dist < 1.0e-6 ? 0 : Math.min(1.0, step / dist);
        double newYaw = MathHelper.wrapDegrees(serverYaw + yawErr * scale);
        double newPitch = MathHelper.clamp(serverPitch + pitchErr * scale, -90.0, 90.0);

        if (!active.silent) {
            // The visible camera is turned per frame (see onFrame), not once per tick,
            // so the motion is as smooth as the frame rate instead of 20 Hz steps.
            frameYaw = MathUtil.angleDelta(player.getYaw(), newYaw);
            framePitch = newPitch - player.getPitch();
            frameApplied = 0;
            frameStart = System.nanoTime();
        }
        serverYaw = newYaw;
        serverPitch = newPitch;

        // A visible request completes once reached. A silent one HOLDS until it expires:
        // dropping it early would make the next movement packet carry the real view
        // again (a back-and-forth flick the server sees) before the module re-requests.
        if (!active.silent && Math.abs(MathUtil.angleDelta(serverYaw, active.targetYaw)) < 0.05
                && Math.abs(serverPitch - active.targetPitch) < 0.05
                && ageTicks >= 2) {
            active = null;
        }
    }

    // ------------------------------------------------------------------
    // Per-frame visible turning
    // ------------------------------------------------------------------

    private static double frameYaw;
    private static double framePitch;
    private static double frameApplied;
    private static long frameStart;

    /**
     * Called every rendered frame. Applies the fraction of this tick's turn that the
     * elapsed time calls for, as a delta, so the player's own mouse movement still adds
     * on top instead of being overwritten.
     */
    public static void onFrame(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || frameApplied >= 1.0 || (frameYaw == 0 && framePitch == 0)) {
            return;
        }
        double frac = Math.min(1.0, (System.nanoTime() - frameStart) / 50_000_000.0);
        applyFrameFraction(player, frac);
    }

    /**
     * Pre-movement stage, before modules tick: completes last tick's visible turn so that
     * modules judge their aim against the rotation this tick will actually use.
     */
    public static void settle(MinecraftClient client) {
        if (client.player != null) {
            flushFrameTurn(client.player);
        }
    }

    private static void flushFrameTurn(ClientPlayerEntity player) {
        if (frameApplied < 1.0 && (frameYaw != 0 || framePitch != 0)) {
            applyFrameFraction(player, 1.0);
        }
        frameYaw = framePitch = 0;
        frameApplied = 1.0;
    }

    private static void applyFrameFraction(ClientPlayerEntity player, double frac) {
        double d = frac - frameApplied;
        if (d <= 0) {
            return;
        }
        frameApplied = frac;
        player.setYaw((float) (player.getYaw() + frameYaw * d));
        player.setPitch((float) MathHelper.clamp(player.getPitch() + framePitch * d, -90.0, 90.0));
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

    /**
     * @return true if the rotation an interaction sent now would use is within
     * {@code toleranceDeg} of the point's angles: the packet rotation while a silent request
     * is active, otherwise the player's live view (not a turn that is only planned).
     */
    public static boolean isAimedAt(Vec3d point, double toleranceDeg) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || point == null) {
            return false;
        }
        boolean silent = isSilentActive();
        double yaw = silent ? serverYaw : player.getYaw();
        double pitch = silent ? serverPitch : player.getPitch();
        double[] req = requiredAngles(point);
        // NaN input fails both comparisons.
        return Math.abs(MathUtil.angleDelta(yaw, req[0])) <= toleranceDeg
                && Math.abs(pitch - req[1]) <= toleranceDeg;
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
