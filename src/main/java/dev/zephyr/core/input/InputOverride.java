package dev.zephyr.core.input;

import dev.zephyr.core.timing.TickPhase;
import net.minecraft.util.PlayerInput;

/**
 * One-tick movement-input requests from automation modules (jump / forward / sprint).
 *
 * <p>A module calls {@link #requestJump()} or {@link #requestForward(boolean)} from its
 * pre-movement {@code onTick}. {@code MixinKeyboardInput} applies the request when vanilla
 * reads the keyboard later in the SAME client tick ({@code ClientPlayerEntity#tickMovementInput}),
 * so the result is the same {@code PlayerInput} a human key press would produce - gliding
 * start, jumping, sprint state and the movement packets all follow vanilla code paths.
 *
 * <p>Requests are stamped with {@link TickPhase#tickId()} and silently expire the next tick:
 * a module that stops asking (disabled, aborted, world change) can never leave a key stuck.
 */
public final class InputOverride {

    private InputOverride() {
    }

    private static long stamp = Long.MIN_VALUE;
    private static boolean jump;
    private static boolean forward;
    private static boolean sprint;
    private static long lastJumpTick = Long.MIN_VALUE;

    private static void touch() {
        long now = TickPhase.tickId();
        if (stamp != now) {
            stamp = now;
            jump = false;
            forward = false;
            sprint = false;
        }
    }

    /** Presses jump for this tick. */
    public static void requestJump() {
        touch();
        jump = true;
        lastJumpTick = TickPhase.tickId();
    }

    /** True if a jump was requested in the previous tick (a fresh key edge needs a gap tick). */
    public static boolean jumpedLastTick() {
        return lastJumpTick == TickPhase.tickId() - 1;
    }

    /** Holds forward (and optionally sprint) for this tick. */
    public static void requestForward(boolean sprinting) {
        touch();
        forward = true;
        sprint = sprint || sprinting;
    }

    /** True while a request made this tick is waiting to be applied. */
    public static boolean active() {
        return stamp == TickPhase.tickId() && (jump || forward || sprint);
    }

    public static boolean forceForward() {
        return stamp == TickPhase.tickId() && forward;
    }

    /** Merges this tick's requests into the keyboard state (requests only ever ADD presses). */
    public static PlayerInput apply(PlayerInput in) {
        if (stamp != TickPhase.tickId()) {
            return in;
        }
        return new PlayerInput(in.forward() || forward, in.backward() && !forward, in.left(), in.right(),
                in.jump() || jump, in.sneak(), in.sprint() || sprint);
    }
}
