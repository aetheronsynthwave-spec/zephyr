package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.CombatCoordinator;
import dev.zephyr.core.combat.SwapManager;
import dev.zephyr.core.flight.DiveLearner;
import dev.zephyr.core.flight.FlightModel;
import dev.zephyr.core.input.InputOverride;
import dev.zephyr.core.inventory.ElytraLocator;
import dev.zephyr.core.inventory.GearSlots;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetLock;
import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.core.world.HitGeometry;
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/**
 * Elytra + mace bot.
 *
 * <pre>
 *  PREPARE -> TAKEOFF -> CLIMB -> DIVE -> STRIKE -> RECOVER -> CLIMB ...
 * </pre>
 *
 * <ul>
 *   <li><b>CLIMB</b> gains height while closing in on the target.</li>
 *   <li><b>DIVE</b> flies the heading that {@link FlightModel#bestDive} forecasts passes closest
 *       to the target, re-solved every tick.</li>
 *   <li><b>STRIKE</b> starts when {@link FlightModel#ticksToSmash} says a free fall from here
 *       reaches the target: the elytra comes off (vanilla gives no smash bonus while gliding)
 *       and the mace hit goes out as soon as the target is in reach.</li>
 *   <li><b>RECOVER</b> puts the elytra back and restarts the glide, using the Wind Burst launch
 *       as the start of the next climb. A fall that stops looking like a hit recovers at once.</li>
 * </ul>
 */
public final class ElytraMaceBot extends Module {

    private static final int PRIORITY = 5;
    /** Slack around the hitbox for the crosshair test, in blocks. Not tuned in game. */
    private static final double AIM_MARGIN = 0.1;

    private final NumberSetting range = num("range", "Target range", 16, 128, 2, 64);
    private final NumberSetting height = num("diveHeight", "Dive height above target", 8, 60, 1, 12);
    private final NumberSetting minFall = num("smashFall", "Min fall distance to smash", 1.6, 20, 0.1, 1.6);
    private final NumberSetting diveAt = num("rocketAt", "Rocket down once rise speed is under", -0.5, 3.0, 0.1, 1.0);
    private final NumberSetting reach = num("reach", "Strike reach", 2.5, 3.0, 0.05, 2.95);
    private final NumberSetting minHealth = num("minHealth", "Stop below health", 2, 20, 1, 8);
    private final BooleanSetting requireWindBurst = bool("windBurst", "Require Wind Burst mace", true);
    private final BooleanSetting respectShield = bool("respectShield", "Hold strike into raised shield", true);
    /** Longest free fall committed to: shorter keeps the rocket pushing longer. */
    private final NumberSetting commit = num("dropAhead", "Elytra off this many ticks before impact", 1, 8, 1, 2);
    private final BooleanSetting aiMode = bool("aiMode", "AI mode (learns timing from misses)", false);
    private final BooleanSetting axeBreak = bool("axeBreak", "Break shields with a hotbar axe first", true);
    private final BooleanSetting targetMobs = bool("targetMobs", "Practise on hostile mobs too", true);

    private enum State { IDLE, PREPARE, TAKEOFF, CLIMB, DIVE, STRIKE, RECOVER }

    private final TargetLock lock = new TargetLock();
    private State state = State.IDLE;
    private int stateTicks;
    private int sinceRocket = 99;
    private int failures;
    private long actTick = Long.MIN_VALUE;
    private boolean slamPending;
    private int strikes;
    private int misses;
    private int clutches;
    private int shieldBreaks;
    private DiveLearner learner;
    /** An attempt is being judged: when it started, and whether its hit was sent. */
    private boolean attempting;
    private long attemptTick;
    private long hitSentTick = -1;
    private long strikeTick;
    private long clutchTick;

    private int trackedId = -1;
    private Vec3d lastTargetPos;
    private Vec3d targetVel = Vec3d.ZERO;
    /** The target's unsmoothed movement over the last tick; valid only when {@code stepKnown}. */
    private Vec3d targetStep = Vec3d.ZERO;
    private boolean stepKnown;

    public ElytraMaceBot() {
        super("ElytraMaceBot", ModuleCategory.COMBAT, 0);
        lock.extra = e -> targetMobs.get() && e instanceof HostileEntity;
    }

    @Override
    protected String exclusiveGroup() {
        return "mace";
    }

    /** CombatCoordinator: other hitters hold off while a dive is under way. */
    public boolean slamPending() {
        return slamPending;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onEnable() {
        reset();
        strikes = 0;
        misses = 0;
        clutches = 0;
        shieldBreaks = 0;
    }

    @Override
    protected void onDisable() {
        if (learner != null) {
            learner.save();
        }
        attempting = false;
        RotationManager.clear(PRIORITY);
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self != null && !self.isOnGround() && !GearSlots.wearingElytra(self) && state.ordinal() >= State.STRIKE.ordinal()) {
            int h = GearSlots.counterpartHotbarSlot(self);
            if (h == -1 || !GearSlots.swapWorn(client, h)) {
                say(self, "stopped mid-air with the chestplate on - put the elytra back on!");
            }
        }
        reset();
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        reset();
    }

    private void reset() {
        enter(State.IDLE);
        failures = 0;
        slamPending = false;
        trackedId = -1;
        lastTargetPos = null;
        targetVel = Vec3d.ZERO;
        targetStep = Vec3d.ZERO;
        stepKnown = false;
        lock.clear();
    }

    private void enter(State next) {
        state = next;
        stateTicks = 0;
    }

    private void stop(ClientPlayerEntity self, String reason) {
        say(self, reason);
        setEnabled(false);
    }

    private static void say(ClientPlayerEntity self, String message) {
        self.sendMessage(Text.literal("[Zephyr] ElytraMaceBot: " + message), false);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null || client.interactionManager == null) {
            reset();
            return;
        }
        if (!self.isAlive()) {
            reset();
            return;
        }
        if (self.getHealth() + self.getAbsorptionAmount() < minHealth.get() && state != State.RECOVER
                && GearSlots.wearingElytra(self)) {
            stop(self, "health below limit, stopped.");
            return;
        }
        stateTicks++;
        sinceRocket++;
        slamPending = false;

        LivingEntity target = lock.update(client, range.get(), false);
        trackTarget(target);
        judgeAttempt(self);

        // The fall save goes first: an inventory click in the same tick would block the item use.
        if (fallingFromMiss(self, target) && windClutch(client, self)) {
            return;
        }

        switch (state) {
            case IDLE -> {
                if (target != null) {
                    enter(State.PREPARE);
                } else if (self.isGliding()) {
                    steer(self.getYaw(), -5.0); // nothing to hit: stay level
                }
            }
            case PREPARE -> prepare(client, self, target);
            case TAKEOFF -> takeoff(client, self, target);
            case CLIMB -> climb(client, self, target);
            case DIVE -> dive(client, self, target);
            case STRIKE -> strike(client, self, target);
            case RECOVER -> recover(client, self, target);
        }
    }


    private void prepare(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (target == null) {
            enter(State.IDLE);
            return;
        }
        if (stateTicks > 200) {
            stop(self, "could not get the gear ready (is the inventory busy?).");
            return;
        }
        if (!ready(client, self, this::isStrikeMace, requireWindBurst.get() ? "Wind Burst mace" : "mace")
                || !ready(client, self, GearSlots::isFirework, "firework rockets")) {
            return;
        }
        ItemStack worn = GearSlots.worn(self);
        if (GearSlots.isElytra(worn) && !GearSlots.isUsableElytra(worn)) {
            stop(self, "the elytra is about to break.");
            return;
        }
        if (!GearSlots.isElytra(worn)) {
            ElytraLocator.Found elytra = GearSlots.locateElytra(self);
            if (elytra.usable()) {
                equipElytra(client, self, elytra);
            } else if (stateTicks > 10 && client.currentScreen == null) {
                // The grace period covers an inventory that is still settling.
                stop(self, "no usable elytra in the chest slot, hotbar or inventory.");
            }
            return;
        }
        if (!ready(client, self, GearSlots::isChestplate, "chestplate")) {
            return;
        }
        enter(self.isGliding() ? State.CLIMB : State.TAKEOFF);
    }

    /** True when the item is in the hotbar; otherwise stages it from the inventory (or stops). */
    private boolean ready(MinecraftClient client, ClientPlayerEntity self, Predicate<ItemStack> pred, String what) {
        if (InventoryManager.findHotbarSlot(self, pred) != -1) {
            return true;
        }
        if (!GearSlots.has(self, pred)) {
            stop(self, "no " + what + ".");
            return false;
        }
        if (!acted() && GearSlots.stageToHotbar(client, pred, maceSlot(self), rocketSlot(self),
                GearSlots.counterpartHotbarSlot(self))) {
            noteAct();
        }
        return false;
    }

    /**
     * One step towards wearing the elytra found at {@code at}: the chest swap when it is in the
     * hotbar, otherwise first the move from the main inventory into the hotbar.
     */
    private void equipElytra(MinecraftClient client, ClientPlayerEntity self, ElytraLocator.Found at) {
        if (acted()) {
            return;
        }
        boolean sent = switch (at.place()) {
            case HOTBAR -> GearSlots.swapWorn(client, at.slot());
            case MAIN -> GearSlots.stageToHotbar(client,
                    at.usable() ? GearSlots::isUsableElytra : GearSlots::isElytra,
                    maceSlot(self), rocketSlot(self),
                    InventoryManager.findHotbarSlot(self, s -> s.isOf(Items.WIND_CHARGE)),
                    InventoryManager.findHotbarSlot(self, s -> ItemUtil.id(s).endsWith("_axe")));
            case WORN, NONE -> false;
        };
        if (sent) {
            noteAct();
        }
    }

    private void takeoff(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (self.isGliding()) {
            enter(State.RECOVER); // same choice as after a bounce: dive now if high enough, else climb
            return;
        }
        if (!GearSlots.wearingUsableElytra(self)) {
            enter(State.PREPARE);
            return;
        }
        if (stateTicks > 60) {
            retry(self, "the glide never started (no headroom?).");
            return;
        }
        // Close to the target, a wind charge at the feet is a much faster launch than climbing.
        int wind = InventoryManager.findHotbarSlot(self, s -> s.isOf(Items.WIND_CHARGE));
        if (self.isOnGround() && target != null && lock.inRange() && wind != -1 && self.distanceTo(target) < 9.0f
                && !acted() && !ItemUtil.coolingDown(self, self.getInventory().getStack(wind))) {
            RotationManager.clear(PRIORITY);
            self.setPitch(90.0f);
            if (useItem(client, self, wind, ItemUtil::isWindCharge)) {
                InputOverride.requestJump();
                return;
            }
        }
        if (target != null) {
            steer(MathUtil.yawTo(self.getEyePos(), target.getEntityPos()), -45.0);
        }
        // A glide starts from a fresh jump press in the air: press on every other tick.
        if (!InputOverride.jumpedLastTick() && (self.isOnGround() || self.getVelocity().y < 0.15 || stateTicks >= 4)) {
            InputOverride.requestJump();
        }
    }

    private void retry(ClientPlayerEntity self, String reason) {
        if (++failures >= 4) {
            stop(self, reason);
        } else {
            enter(State.PREPARE);
        }
    }

    private void climb(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (!self.isGliding()) {
            enter(self.isOnGround() ? State.PREPARE : State.RECOVER);
            return;
        }
        if (target == null) {
            enter(State.IDLE);
            return;
        }
        double above = self.getY() - target.getY();
        double dx = target.getX() - self.getX(), dz = target.getZ() - self.getZ();
        double horiz = Math.hypot(dx, dz);
        double bearing = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double want = height.get();

        // Enough height and a steep enough line: dive.
        if (above >= want * 0.85 && horiz <= above * 0.9 && !target.isGliding()) {
            failures = 0;
            enter(State.DIVE);
            dive(client, self, target);
            return;
        }
        double pitch;
        if (above < want) {
            // Climb towards a point over the target; nearly vertical when already underneath it.
            pitch = -MathHelper.clamp(Math.toDegrees(Math.atan2(want - above, Math.max(horiz, 1.0))), 35.0, 82.0);
        } else {
            pitch = -2.0; // high enough: run in level
        }
        if (blockedAhead(self)) {
            pitch = -80.0;
        }
        steer(bearing, pitch);
        // Rocket only while the current momentum will not already carry us high enough.
        double carried = above + Math.max(0.0, self.getVelocity().y) * 8.0;
        if ((above < want && carried < want) || self.getVelocity().length() < 0.7) {
            tryRocket(client, self);
        }
    }

    private void dive(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (!self.isGliding()) {
            enter(self.isOnGround() ? State.PREPARE : State.RECOVER);
            return;
        }
        if (target == null) {
            enter(State.IDLE);
            return;
        }
        if (target.isGliding()) {
            enter(State.CLIMB);
            return;
        }
        slamPending = true;
        if (!attempting) {
            attempting = true;
            hitSentTick = -1;
            attemptTick = TickPhase.tickId();
            if (aiMode.get()) {
                if (learner == null) {
                    learner = new DiveLearner();
                }
                learner.choose(targetKind());
            }
        }
        Vec3d pos = self.getEntityPos();
        Box box = aimBox(self, target);
        double bearing = MathUtil.yawTo(pos, box.getCenter());
        int impact = smashIn(client, self, target.getBoundingBox());
        // Already right over their head with the fall banked: no forecast needed, just drop and swing.
        Box real = target.getBoundingBox();
        boolean overhead = self.fallDistance >= minFall.get() && target.hurtTime <= 2
                && pos.y > real.maxY - 0.2 && pos.y < real.maxY + 1.5
                && real.squaredMagnitude(self.getEyePos()) <= reach.get() * reach.get();
        if (swapAllowed(self, target) && (overhead || impact > 0 && target.hurtTime <= impact)) {
            enter(State.STRIKE);
            strike(client, self, target); // send the chest swap this tick
            return;
        }
        FlightModel.Line line = FlightModel.bestDive(pos, self.getVelocity(), bearing, box, leadVel(), true);
        // A line that will not connect is abandoned while there is still height to level out:
        // finding that out at the bottom means flying (or falling) into the ground.
        double pullOut = Math.max(0.0, -self.getVelocity().y) * 3.0 + 1.0;
        boolean low = pos.y - box.maxY < pullOut;
        if ((low && line.miss() > 2.0) || pos.y < box.maxY + 0.5 || blockedBefore(self, box)) {
            misses++;
            enter(State.CLIMB);
            steer(bearing, -60.0);
            return;
        }
        // While the target cannot be hurt again yet, keep the height instead of gliding down.
        if (target.hurtTime > 4) {
            steer(bearing, -25.0);
            return;
        }
        steer(line.yaw(), line.pitch());
        if (self.getVelocity().y > (learning() ? learner.value(DiveLearner.DIVE_AT) : diveAt.get())) {
            return; // still rising fast off the bounce
        }
        // Rocket down: boost once the nose is on the line (an earlier rocket may still be burning).
        if (Math.abs(self.getPitch() - line.pitch()) < 35.0 && line.pitch() > 10.0 && self.distanceTo(target) > 3.5f) {
            tryRocket(client, self);
        }
    }

    /** Forecast ticks until a smash if the elytra came off now, or -1. */
    private int smashIn(MinecraftClient client, ClientPlayerEntity self, Box box) {
        int lag = Math.min(6, 1 + CombatCoordinator.pingTicks(client));
        int ahead = learning() ? (int) learner.value(DiveLearner.COMMIT) : commit.get().intValue();
        if (targetKind().equals("fast")) {
            ahead = Math.min(ahead, 2); // less time for the target to run out from under the fall
        }
        return FlightModel.ticksToSmash(self.getEntityPos(), self.getVelocity(), self.getYaw(), self.getPitch(),
                self.fallDistance, lag, box, leadVel(), reach.get() - 0.25, minFall.get(), lag + ahead,
                boosting(client, self));
    }

    private boolean swapAllowed(ClientPlayerEntity self, LivingEntity target) {
        // A raised shield is no reason to wait: the strike breaks it with an axe, or the dive
        // line (see aimBox) comes down behind it.
        return lock.inRange() && maceSlot(self) != -1 && GearSlots.counterpartHotbarSlot(self) != -1;
    }

    private void strike(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            enter(State.RECOVER);
            return;
        }
        slamPending = true;
        Vec3d eye = self.getEyePos();
        Box box = target.getBoundingBox();
        Vec3d aim = MathUtil.bestAimPoint(self, target, self.getYaw(), self.getPitch());
        // The turn lands next tick, so aim from where the eyes will be at where the target will be.
        Vec3d nextEye = eye.add(self.getVelocity());
        Vec3d nextAim = aim.add(targetStep);
        steer(MathUtil.yawTo(nextEye, nextAim), MathUtil.pitchTo(nextEye, nextAim));

        if (GearSlots.wearingElytra(self)) {
            if (stateTicks > 8) {
                misses++;
                enter(State.CLIMB); // the chest click was never accepted
            } else if (!acted()) {
                int h = GearSlots.counterpartHotbarSlot(self);
                if (h != -1 && GearSlots.swapWorn(client, h)) {
                    noteAct();
                }
            }
            return;
        }
        boolean inReach = box.squaredMagnitude(eye) <= reach.get() * reach.get();
        boolean hittable = inReach && hittable(self, target);
        if (!inReach && Math.hypot(box.getCenter().x - eye.x, box.getCenter().z - eye.z) > 0.8) {
            InputOverride.requestForward(targetVel.horizontalLength() > 0.2); // air control: drift towards a target that moved
        }
        // Stun slam: the axe disables the shield this tick, the mace lands on a following one.
        if (hittable && !acted() && !self.isGliding() && axeBreak.get()
                && CombatCoordinator.shieldBlocks(target, self)) {
            int axe = InventoryManager.findHotbarSlot(self, s -> ItemUtil.id(s).endsWith("_axe"));
            if (axe != -1 && CombatActions.attributeSwapAttack(client, axe, target, 3)) {
                noteAct();
                CombatCoordinator.noteShieldBroken(target);
                shieldBreaks++;
                return;
            }
        }
        if (hittable && !acted() && !self.isGliding() && self.fallDistance >= minFall.get() && target.hurtTime <= 1
                && !(respectShield.get() && CombatCoordinator.shieldBlocks(target, self))) {
            int mace = maceSlot(self);
            if (mace == -1) {
                stop(self, "the mace left the hotbar.");
                return;
            }
            if (CombatActions.attributeSwapAttack(client, mace, target, 1)) {
                noteAct();
                strikes++;
                strikeTick = TickPhase.tickId();
                hitSentTick = strikeTick;
                enter(State.RECOVER);
            }
            return;
        }
        // Hold a wind charge while falling so a save needs no slot change at the last moment
        // (the mace hit swaps from whatever is held).
        int wind = InventoryManager.findHotbarSlot(self, s -> s.isOf(Items.WIND_CHARGE));
        if (!inReach && self.fallDistance > 9.0 && wind != -1 && self.getInventory().getSelectedSlot() != wind && !SwapManager.pending()) {
            SwapManager.select(client, wind);
        }
        // No longer a hit (target dodged, or we are past it): get the elytra back before the ground.
        if (!inReach && stateTicks > 3 && (eye.y < box.minY + 0.5 || stateTicks > 20 || !stillReaching(self, box))) {
            misses++;
            enter(State.RECOVER);
            recover(client, self, target);
            return;
        }
        if (self.isOnGround()) {
            misses++;
            enter(State.RECOVER);
        }
    }

    /**
     * Everything a hit sent this tick needs: the locked target inside the target range, in
     * reach both now and one movement step ahead, under the crosshair, and a visible point of
     * its hitbox within reach.
     */
    private boolean hittable(ClientPlayerEntity self, LivingEntity target) {
        if (target != lock.get() || !lock.inRange() || !stepKnown || target.getId() != trackedId) {
            return false;
        }
        Vec3d eye = self.getEyePos();
        Box box = target.getBoundingBox();
        double max = reach.get();
        return HitGeometry.withinReachThroughStep(eye, box, targetStep, max)
                && HitGeometry.lookCrosses(eye, self.getYaw(), self.getPitch(), box, max, AIM_MARGIN)
                && RaycastValidator.visibleWithin(eye, box, max, self);
    }

    /** With the elytra already off: does the remaining free fall still pass within reach? */
    private boolean stillReaching(ClientPlayerEntity self, Box box) {
        return FlightModel.ticksToSmash(self.getEntityPos(), self.getVelocity(), self.getYaw(), self.getPitch(),
                self.fallDistance, 0, box, targetVel, reach.get() + 0.3, minFall.get(), 10, false) > 0;
    }

    private void recover(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (target != null) {
            // Already face the target, nose down: the dive then starts without a turn.
            Vec3d aim = target.getBoundingBox().getCenter();
            steer(MathUtil.yawTo(self.getEyePos(), aim), Math.max(30.0, MathUtil.pitchTo(self.getEyePos(), aim)));
        }
        if (!GearSlots.wearingElytra(self)) {
            // Not found is not final: keep looking until the timeout, the fall save stays armed.
            ElytraLocator.Found elytra = GearSlots.locateElytra(self);
            if (stateTicks > 40) {
                stop(self, elytra.place() == ElytraLocator.Place.NONE
                        ? "no elytra in the chest slot, hotbar or inventory."
                        : "could not put the elytra back on.");
                return;
            }
            equipElytra(client, self, elytra);
            return;
        }
        if (self.isGliding()) {
            int rockets = rocketSlot(self);
            if (rockets != -1 && self.getInventory().getSelectedSlot() != rockets && !SwapManager.pending()) {
                SwapManager.select(client, rockets); // in hand before the dive, so no tick is lost
            }
            // The bounce is all the height needed; only climb when it left us too low or too far out.
            boolean canDive = target != null && !target.isGliding() && self.getY() - target.getY() > target.getHeight() + 1.5
                    && Math.hypot(target.getX() - self.getX(), target.getZ() - self.getZ())
                    < 3.0 + (self.getY() - target.getY()) * 1.2;
            if (canDive) {
                enter(State.DIVE);
                dive(client, self, target);
            } else if (stateTicks > 8 || self.getVelocity().y < 0.2) {
                enter(State.CLIMB);
            }
            return;
        }
        if (self.isOnGround()) {
            enter(State.PREPARE);
            return;
        }
        if (stateTicks > 80) {
            retry(self, "could not start gliding again.");
            return;
        }
        if (!InputOverride.jumpedLastTick()) {
            InputOverride.requestJump();
        }
    }

    /**
     * Last-moment save for a missed drop: a wind charge thrown straight down just before the
     * ground cancels the fall damage. Needs wind charges in the hotbar.
     */
    private boolean learning() {
        return aiMode.get() && learner != null;
    }

    /**
     * Decides how the current attempt went. A landed Wind Burst smash launches us upwards, so
     * that launch is the proof of a hit: a swing the target blocked or dodged gives none.
     */
    private void judgeAttempt(ClientPlayerEntity self) {
        if (!attempting) {
            return;
        }
        long now = TickPhase.tickId();
        boolean hit = hitSentTick >= 0 && now > hitSentTick && self.getVelocity().y > 0.4;
        boolean failed = hitSentTick >= 0 ? now - hitSentTick > 6
                : (state != State.DIVE && state != State.STRIKE && now > attemptTick + 1) || now - attemptTick > 100;
        if (!hit && !failed) {
            return;
        }
        attempting = false;
        if (learning()) {
            // Quick hits score highest, so the learner prefers speed as well as accuracy.
            learner.learn(hit ? MathHelper.clamp(20.0 / (now - attemptTick), 0.2, 1.0) : 0.0);
        }
    }

    private String targetKind() {
        double speed = targetVel.horizontalLength();
        return speed > 0.3 ? "fast" : speed > 0.08 ? "moving" : "still";
    }

    /** Target velocity as used for aiming ahead (the AI mode tunes how far). */
    private Vec3d leadVel() {
        return learning() ? targetVel.multiply(learner.value(DiveLearner.LEAD)) : targetVel;
    }

    /**
     * The box the dive aims for. Against a raised shield with no axe to break it, aim a little
     * behind the target: shields only cover the front.
     */
    private Box aimBox(ClientPlayerEntity self, LivingEntity target) {
        Box box = target.getBoundingBox();
        boolean hasAxe = axeBreak.get()
                && InventoryManager.findHotbarSlot(self, s -> ItemUtil.id(s).endsWith("_axe")) != -1;
        if (hasAxe || !target.isBlocking()) {
            return box;
        }
        Vec3d facing = target.getRotationVector(0.0f, target.getHeadYaw());
        Vec3d flat = new Vec3d(facing.x, 0.0, facing.z);
        return flat.lengthSquared() < 1.0e-4 ? box : box.offset(flat.normalize().multiply(-0.9));
    }

    private boolean windClutch(MinecraftClient client, ClientPlayerEntity self) {
        double fallSpeed = -self.getVelocity().y;
        if (TickPhase.tickId() - clutchTick < 10) {
            return false;
        }
        int slot = InventoryManager.findHotbarSlot(self, s -> s.isOf(Items.WIND_CHARGE));
        if (slot == -1) {
            return false;
        }
        Vec3d feet = self.getEntityPos();
        if (RaycastValidator.canSee(feet, feet.add(0, -(fallSpeed * 3.0 + 1.0), 0), self)) {
            return false; // ground is still more than three ticks away
        }
        if (ItemUtil.coolingDown(self, self.getInventory().getStack(slot))) {
            return false; // leave the tick to recovery
        }
        RotationManager.clear(PRIORITY);
        self.setPitch(90.0f);
        if (useItem(client, self, slot, ItemUtil::isWindCharge)) {
            clutchTick = TickPhase.tickId();
            clutches++;
            return true;
        }
        return false;
    }

    /** Falling fast towards the ground with no mace hit left to break the fall. */
    private boolean fallingFromMiss(ClientPlayerEntity self, LivingEntity target) {
        if (state != State.STRIKE && state != State.RECOVER && state != State.CLIMB) {
            return false;
        }
        if (self.isOnGround() || self.getVelocity().y > -0.6 || self.fallDistance < 4.0
                || TickPhase.tickId() - strikeTick < 3) { // a landed hit's Wind Burst is still arriving
            return false;
        }
        if (state != State.STRIKE || target == null) {
            return true;
        }
        Box box = target.getBoundingBox();
        boolean inReach = box.squaredMagnitude(self.getEyePos()) <= reach.get() * reach.get();
        if (inReach && hittable(self, target)) {
            return false;
        }
        // No valid hit this tick: save as soon as the fall stops lining up, and in any case once the
        // ground is about to arrive (a target that moved can look reachable for a few ticks).
        Vec3d feet = self.getEntityPos();
        double nextTicks = -self.getVelocity().y * 2.0;
        // In reach but refused (aim, sight or movement): only the ground distance decides.
        return !inReach && !stillReaching(self, box)
                || !RaycastValidator.canSee(feet, feet.add(0, -nextTicks, 0), self);
    }

    // ------------------------------------------------------------------
    // Rockets and helpers
    // ------------------------------------------------------------------

    private void tryRocket(MinecraftClient client, ClientPlayerEntity self) {
        if (!self.isGliding() || acted() || sinceRocket < 5 || SwapManager.pending()) {
            return;
        }
        int slot = rocketSlot(self);
        if (slot == -1) {
            stop(self, "out of firework rockets.");
            return;
        }
        if (self.getInventory().getSelectedSlot() != slot) {
            if (SwapManager.select(client, slot)) {
                noteAct(); // the use itself goes out next tick
            }
            return;
        }
        if (boosting(client, self) || ItemUtil.coolingDown(self, self.getMainHandStack())) {
            return;
        }
        if (useItem(client, self, slot, GearSlots::isFirework)) {
            sinceRocket = 0;
        }
    }

    /**
     * One item use from a hotbar slot at the live view. Any result but FAIL means the packet
     * went out, so it counts as this tick's act even if the client prediction passed.
     */
    private boolean useItem(MinecraftClient client, ClientPlayerEntity self, int slot, Predicate<ItemStack> wanted) {
        if (CombatActions.interactItemFromSlot(client, slot, wanted, self.getYaw(), self.getPitch())
                == ActionResult.FAIL) {
            return false;
        }
        noteAct();
        return true;
    }

    private static boolean boosting(MinecraftClient client, ClientPlayerEntity self) {
        return !client.world.getEntitiesByClass(FireworkRocketEntity.class,
                self.getBoundingBox().expand(8.0), r -> r.getOwner() == self && !r.isRemoved()).isEmpty();
    }

    private boolean isStrikeMace(ItemStack s) {
        return ItemUtil.isMace(s) && (!requireWindBurst.get() || ItemUtil.enchantLevel(s, "wind_burst") > 0);
    }

    private int maceSlot(ClientPlayerEntity self) {
        return InventoryManager.findHotbarSlot(self, this::isStrikeMace);
    }

    private int rocketSlot(ClientPlayerEntity self) {
        return InventoryManager.findHotbarSlot(self, GearSlots::isFirework);
    }

    private boolean acted() {
        return actTick == TickPhase.tickId();
    }

    private void noteAct() {
        actTick = TickPhase.tickId();
    }

    /** A block within a few ticks of travel along the current flight direction. */
    private static boolean blockedAhead(ClientPlayerEntity self) {
        Vec3d v = self.getVelocity();
        if (v.lengthSquared() < 0.09) {
            return false;
        }
        Vec3d from = self.getEntityPos();
        return !RaycastValidator.canSee(from, from.add(v.multiply(6.0)), self);
    }

    /** Something solid in the flight path that is closer than the target. */
    private static boolean blockedBefore(ClientPlayerEntity self, Box box) {
        Vec3d from = self.getEntityPos();
        Vec3d ahead = from.add(self.getVelocity().multiply(4.0));
        return ahead.y > box.maxY && !RaycastValidator.canSee(from, ahead, self);
    }

    private void steer(double yaw, double pitch) {
        RotationManager.request(RotationRequest.to(MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90.0, 90.0))
                .speed(55.0, 80.0)
                .jitter(0.0)
                .silent(false) // elytra physics follow the real view
                .priority(PRIORITY)
                .validWhile(this::isEnabled)
                .expiresAfter(3)
                .build());
    }

    /** Smoothed per-tick velocity of the target (teleports ignored). */
    private void trackTarget(LivingEntity target) {
        stepKnown = false;
        targetStep = Vec3d.ZERO;
        if (target == null) {
            trackedId = -1;
            lastTargetPos = null;
            targetVel = Vec3d.ZERO;
            return;
        }
        Vec3d p = target.getEntityPos();
        if (target.getId() == trackedId && lastTargetPos != null) {
            Vec3d delta = p.subtract(lastTargetPos);
            stepKnown = HitGeometry.finite(delta) && delta.length() <= 4.0;
            targetStep = stepKnown ? delta : Vec3d.ZERO;
            targetVel = stepKnown ? targetVel.multiply(0.5).add(delta.multiply(0.5)) : Vec3d.ZERO;
        } else {
            targetVel = Vec3d.ZERO;
        }
        trackedId = target.getId();
        lastTargetPos = p;
    }

    @Override
    public String hudLabel() {
        return name + " · " + state.name().toLowerCase() + " · " + strikes + " hits " + misses + " missed " + clutches + " saves " + shieldBreaks + " shields" + (aiMode.get() ? " AI" : "");
    }
}
