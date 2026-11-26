package dev.zephyr.core.timing;

/**
 * Where the client currently is inside {@code MinecraftClient#tick()}.
 *
 * <p>Vanilla 1.21.11 tick order (verified against the yarn-mapped client jar):
 * <ol>
 *   <li>{@code START_CLIENT_TICK} (Fabric, tick HEAD)</li>
 *   <li>{@code ClientPlayerInteractionManager#tick} - {@code syncSelectedSlot()} flushes any
 *       pending hotbar change as one {@code UpdateSelectedSlotC2SPacket}</li>
 *   <li>{@code handleInputEvents()} - hotbar keys, {@code doAttack}, {@code doItemUse}</li>
 *   <li>{@code ClientWorld#tickEntities} - {@code ClientPlayerEntity#tick} sends
 *       {@code PlayerInputC2SPacket}, sprint state, then {@code sendMovementPackets()}</li>
 *   <li>{@code ClientTickEndC2SPacket}</li>
 *   <li>{@code END_CLIENT_TICK} (Fabric, tick RETURN)</li>
 * </ol>
 *
 * Slot changes and attacks are only legal-looking while the phase is {@link #INPUT} or
 * {@link #PRE_MOVEMENT}: before this tick's movement packet, inside the same tick the
 * server will attribute them to. Anything emitted after the movement packet, after the
 * tick-end packet, or from a render frame between ticks arrives out of order.
 */
public enum TickPhase {
    /** Between ticks (render frames, screen callbacks) or after {@code END_CLIENT_TICK}. */
    IDLE(false),
    /** From tick HEAD through vanilla's slot sync and {@code handleInputEvents}. */
    INPUT(true),
    /** Zephyr's module stage: right before {@code ClientWorld#tickEntities}. */
    PRE_MOVEMENT(true),
    /** {@code sendMovementPackets} has started; this tick's action window is closed. */
    POST_MOVEMENT(false);

    private final boolean actionsAllowed;

    TickPhase(boolean actionsAllowed) {
        this.actionsAllowed = actionsAllowed;
    }

    private static TickPhase current = IDLE;
    /** Monotonic client tick id, advanced at tick HEAD. */
    private static long tickId;

    public static TickPhase current() {
        return current;
    }

    public static long tickId() {
        return tickId;
    }

    /** True while slot updates / attacks / interactions may be emitted this tick. */
    public static boolean actionWindowOpen() {
        return current.actionsAllowed;
    }

    /** {@code START_CLIENT_TICK}. */
    public static void beginTick() {
        tickId++;
        current = INPUT;
    }

    /** Hook right before {@code ClientWorld#tickEntities}. */
    public static void enterPreMovement() {
        current = PRE_MOVEMENT;
    }

    /** {@code ClientPlayerEntity#sendMovementPackets} HEAD. */
    public static void movementPacketsStarted() {
        if (current == INPUT || current == PRE_MOVEMENT) {
            current = POST_MOVEMENT;
        }
    }

    /** {@code END_CLIENT_TICK}. */
    public static void endTick() {
        current = IDLE;
    }
}
