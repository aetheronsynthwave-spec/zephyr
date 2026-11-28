package dev.zephyr.core.combat;

import dev.zephyr.core.timing.TickPhase;

/**
 * Turn-taking between AutoHitCrystal and AnchorMacro / SafeAnchor, which share one hotbar,
 * one aim and one click per tick. A crystal cycle runs to its end; the anchor chain (place,
 * charge, shield, detonate) is then offered the next turn and keeps it while it is making
 * progress, so neither can swap items out from under the other.
 */
public final class ExplosiveTurn {

    /** Ticks an anchor chain may pause before its turn lapses. */
    private static final int PATIENCE = 6;
    /** Ticks crystals hold back so a waiting anchor module can start. */
    private static final int OFFER = 4;
    /** With no crystal cycle for this long, the anchor module may start by itself. */
    private static final int IDLE = 20;
    /** No chain keeps the turn longer than this. */
    private static final int LONGEST = 40;
    private static final long NEVER = -1_000_000L;

    private static long crystalAt = NEVER;
    private static long offeredAt = NEVER;
    private static long anchorAt = NEVER;
    private static long chainStart = NEVER;
    private static long chainEnd = NEVER;

    private ExplosiveTurn() {
    }

    /** AutoHitCrystal is in the middle of a cycle this tick. */
    public static void crystalActive() {
        crystalAt = TickPhase.tickId();
    }

    /** A crystal cycle just ended and an anchor module is stocked: it may go next. */
    public static void offerAnchor() {
        offeredAt = TickPhase.tickId();
    }

    /** The anchor chain is aiming or clicking this tick. */
    public static void anchorProgress() {
        long now = TickPhase.tickId();
        if (now - anchorAt > PATIENCE) {
            chainStart = now;
        }
        anchorAt = now;
        offeredAt = NEVER;
    }

    /** The anchor went off: crystals are next. */
    public static void anchorFinished() {
        anchorAt = NEVER;
        offeredAt = NEVER;
        chainEnd = TickPhase.tickId();
    }

    /** True while crystals should keep their hands off the hotbar and aim. */
    public static boolean anchorHasTurn() {
        long now = TickPhase.tickId();
        return now - offeredAt <= OFFER || now - anchorAt <= PATIENCE && now - chainStart <= LONGEST;
    }

    /** May an anchor module act this tick while AutoHitCrystal is also on? */
    public static boolean anchorMayAct() {
        long now = TickPhase.tickId();
        return anchorHasTurn() || now - crystalAt > IDLE && now - chainEnd > IDLE;
    }
}
