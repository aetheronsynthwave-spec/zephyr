package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.CombatCoordinator;
import dev.zephyr.core.combat.DamageCalculator;
import dev.zephyr.core.combat.SwapManager;
import dev.zephyr.core.input.InputOverride;
import dev.zephyr.core.inventory.GearSlots;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/**
 * ElytraMaceBot - the automated elytra + Wind Burst mace loop.
 *
 * <pre>
 *  PREFLIGHT -> JUMP -> GLIDE_START -> CLIMB -> DIVE -> SWAP_OFF -> STRIKE -> SWAP_ON -> REGLIDE
 *                                        ^                                                  |
 *                                        +--------------------------------------------------+
 * </pre>
 *
 * <p>Why the chest swap exists: vanilla's mace smash bonus requires
 * {@code fallDistance > 1.5 && !attacker.isGliding()}. Gliding holds fall distance at ~1
 * while vertical speed is above -0.5 b/t, so the dive must be steep (it accumulates normally
 * below that), and the elytra has to come off a few ticks before impact so the server sees a
 * non-gliding attacker. Wind Burst then throws the player back up, the elytra goes back on,
 * and the loop repeats.
 *
 * <p>Packet/tick discipline (all through the existing managers):
 * <ul>
 *   <li>Everything runs in the pre-movement stage ({@link TickPhase}); one module action per
 *       tick (rocket use, chest click or attack never share a tick) and inventory clicks are
 *       never in the same tick as an attack ({@link dev.zephyr.core.combat.ActionState}).</li>
 *   <li>Chest swap: one vanilla SWAP click ({@link GearSlots}), at least {@code swapLead}
 *       ticks before the strike so the server has dropped gliding.</li>
 *   <li>Strike: {@link CombatActions#attributeSwapAttack} - slot packet, attack, swing, in that
 *       order, swap-back at least one tick later via {@link SwapManager}.</li>
 *   <li>Rockets: {@link CombatActions#interactItem} with the rocket slot selected through
 *       {@link SwapManager}; vanilla item cooldown, an own-rocket-alive probe and a minimum gap.</li>
 *   <li>Rotations are VISIBLE (client and server flight physics must agree) and go through
 *       {@link RotationManager} at priority 5 with speed limits and jitter.</li>
 *   <li>Jump / glide-start use {@link InputOverride}: the same {@code PlayerInput} a key press makes.</li>
 * </ul>
 */
public final class ElytraMaceBot extends Module {

    private static final int PRIORITY = 5;
    private static final double STANDING_EYE = 1.62;

    private final NumberSetting range = num("range", "Target range", 16, 96, 2, 64);
    private final NumberSetting climbHeight = num("climbHeight", "Dive height above target", 10, 60, 1, 24);
    private final NumberSetting maxHoriz = num("maxHoriz", "Max horizontal dive offset", 8, 64, 1, 32);
    /** Dive only starts when (nearly) overhead: a steep dive is what banks fall distance. */
    private final NumberSetting diveRadius = num("diveRadius", "Start dive within (blocks)", 2, 12, 0.5, 5);
    private final NumberSetting minDivePitch = num("minDivePitch", "Min dive pitch (deg down)", 45, 89, 1, 60);
    private final NumberSetting climbPitch = num("climbPitch", "Climb pitch (deg up)", 30, 80, 1, 55);
    private final NumberSetting minFall = num("minFall", "Min fall distance to smash", 2, 20, 0.5, 5);
    private final NumberSetting reach = num("reach", "Strike reach", 2.5, 3.0, 0.05, 2.95);
    private final NumberSetting swapLead = num("swapLead", "Chest swap -> strike (ticks)", 2, 5, 1, 2);
    private final NumberSetting strikeWindow = num("strikeWindow", "Strike window (ticks)", 3, 15, 1, 8);
    private final NumberSetting maxHurt = num("hurtTime", "Max target hurt time", 0, 10, 1, 4);
    private final NumberSetting rocketGap = num("rocketGap", "Min ticks between rockets", 5, 40, 1, 14);
    private final NumberSetting climbSpeed = num("climbSpeed", "Rocket below speed (b/t)", 0.5, 2.5, 0.05, 1.3);
    private final NumberSetting diveSpeed = num("diveSpeed", "Dive rocket below speed (b/t)", 0.5, 3.0, 0.05, 1.6);
    private final NumberSetting groundClear = num("groundClear", "Min ground clearance (dive)", 3, 20, 1, 6);
    private final NumberSetting minHealth = num("minHealth", "Abort below health", 2, 20, 1, 8);
    private final NumberSetting rotSpeedMin = num("rotMin", "Rotation min deg/t", 5, 120, 5, 25);
    private final NumberSetting rotSpeedMax = num("rotMax", "Rotation max deg/t", 5, 180, 5, 55);
    private final NumberSetting jitter = num("jitter", "Rotation jitter", 0.0, 2.0, 0.05, 0.3);
    private final NumberSetting backTicks = num("backTicks", "Swap-back ticks", 1, 10, 1, 2);
    private final BooleanSetting requireWindBurst = bool("windBurst", "Require Wind Burst mace", true);
    private final BooleanSetting respectShield = bool("respectShield", "Hold strike into raised shield", true);
    private final BooleanSetting rocketInDive = bool("diveRockets", "Rockets during dive", true);
    private final BooleanSetting groundedOnly = bool("groundedOnly", "Only target players not flying", true);
    private final dev.zephyr.core.target.TargetLock lock = new dev.zephyr.core.target.TargetLock();

    private enum State { PREFLIGHT, JUMP, GLIDE_START, CLIMB, DIVE, SWAP_OFF, STRIKE, SWAP_ON, REGLIDE, ABORT }

    private State state = State.PREFLIGHT;
    private int stateTicks;
    private int sinceRocket = 99;
    private int failures;
    private int diveAborts;
    private double extraClimb;
    private int losLost;
    private long swapTick = Long.MIN_VALUE;
    private long strikeTick = Long.MIN_VALUE;
    private long actTick = Long.MIN_VALUE;
    /** True between a successful elytra-off swap and the elytra-on swap (restore on abort/disable). */
    private boolean chestplateOn;
    private boolean slamPending;
    private String abortReason = "";
    private int strikes;
    private int rockets;

    private int trackedId = -1;
    private Vec3d lastTargetPos;
    private Vec3d targetVel = Vec3d.ZERO;

    public ElytraMaceBot() {
        super("ElytraMaceBot", ModuleCategory.COMBAT, 0);
    }

    @Override
    protected String exclusiveGroup() {
        return "mace";
    }

    /** CombatCoordinator hook: a dive/strike on the current target is in progress. */
    public boolean slamPending() {
        return slamPending;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onEnable() {
        resetLoop();
    }

    @Override
    protected void onDisable() {
        slamPending = false;
        RotationManager.clear(PRIORITY);
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        // Never leave the player in the air with the chestplate on if it can be undone.
        if (self != null && chestplateOn && !GearSlots.wearingElytra(self)) {
            int h = GearSlots.counterpartHotbarSlot(self);
            if (h == -1 || !GearSlots.swapWorn(client, h)) {
                self.sendMessage(Text.literal("[Zephyr] ElytraMaceBot stopped with your chestplate on - swap the elytra back."), true);
            }
        }
        resetLoop();
    }

    @Override
    public void onWorldLeave(MinecraftClient client) {
        resetLoop();
    }

    private void resetLoop() {
        enter(State.PREFLIGHT);
        failures = 0;
        diveAborts = 0;
        extraClimb = 0;
        losLost = 0;
        chestplateOn = false;
        slamPending = false;
        trackedId = -1;
        targetVel = Vec3d.ZERO;
        lastTargetPos = null;
        lock.clear();
    }

    private void enter(State next) {
        state = next;
        stateTicks = 0;
    }

    private void abort(String reason) {
        abortReason = reason;
        enter(State.ABORT);
    }

    private void fail(String reason) {
        if (++failures >= 3) {
            abort(reason);
        } else {
            enter(State.PREFLIGHT);
        }
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (self == null || world == null || client.interactionManager == null || !self.isAlive()) {
            resetLoop();
            return;
        }
        stateTicks++;
        sinceRocket++;
        slamPending = false;

        // Own lock: the shared TargetManager target was overwritten by other modules every
        // tick (TargetHUD's 16-block pick vs our 64), flipping the dive between players.
        LivingEntity target = lock.update(client, range.get(), groundedOnly.get());
        trackTarget(target);

        boolean recovering = state == State.SWAP_ON || state == State.REGLIDE || state == State.ABORT;
        if (!recovering && self.getHealth() + self.getAbsorptionAmount() < minHealth.get()) {
            abort("health below limit");
        }

        switch (state) {
            case PREFLIGHT -> preflight(client, self, target);
            case JUMP -> jump(self);
            case GLIDE_START -> glideStart(self, target);
            case CLIMB -> climb(client, self, world, target);
            case DIVE -> dive(client, self, world, target);
            case SWAP_OFF -> swapOff(client, self, target);
            case STRIKE -> strike(client, self, target);
            case SWAP_ON -> swapOn(client, self);
            case REGLIDE -> reglide(self, target);
            case ABORT -> aborting(client, self);
        }
    }

    // ------------------------------------------------------------------
    // PREFLIGHT: gear verification + staging (one click per tick)
    // ------------------------------------------------------------------

    private void preflight(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (target == null) {
            return;
        }
        if (!ready(client, self, this::isStrikeMace, requireWindBurst.get() ? "Wind Burst mace" : "mace")
                || !ready(client, self, GearSlots::isFirework, "firework rockets")) {
            return;
        }
        ItemStack worn = GearSlots.worn(self);
        if (GearSlots.isElytra(worn) && !GearSlots.isUsableElytra(worn)) {
            abort("elytra is about to break");
            return;
        }
        if (!GearSlots.isElytra(worn)) {
            // Equip the elytra from the hotbar; the worn chestplate lands in that same slot.
            if (!ready(client, self, GearSlots::isUsableElytra, "usable elytra")) {
                return;
            }
            int h = InventoryManager.findHotbarSlot(self, GearSlots::isUsableElytra);
            if (!acted() && GearSlots.swapWorn(client, h)) {
                noteAct();
            }
            return;
        }
        if (!ready(client, self, GearSlots::isChestplate, "chestplate")) {
            return;
        }
        if (self.isGliding()) {
            enter(State.CLIMB);
        } else if (self.isOnGround()) {
            enter(State.JUMP);
        } else {
            enter(State.GLIDE_START);
        }
    }

    /** @return true when the item is in the hotbar; otherwise stages it (or aborts if absent). */
    private boolean ready(MinecraftClient client, ClientPlayerEntity self, Predicate<ItemStack> pred, String what) {
        if (InventoryManager.findHotbarSlot(self, pred) != -1) {
            return true;
        }
        if (!GearSlots.has(self, pred)) {
            abort("no " + what);
            return false;
        }
        if (!acted() && GearSlots.stageToHotbar(client, pred, maceSlot(self), rocketSlot(self), GearSlots.counterpartHotbarSlot(self))) {
            noteAct();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // JUMP / GLIDE_START: takeoff through real key input
    // ------------------------------------------------------------------

    private void jump(ClientPlayerEntity self) {
        if (!GearSlots.wearingUsableElytra(self)) {
            enter(State.PREFLIGHT);
            return;
        }
        if (!self.isOnGround()) {
            enter(State.GLIDE_START);
            return;
        }
        if (stateTicks > 20) {
            fail("cannot jump (no headroom?)");
            return;
        }
        InputOverride.requestJump();
    }

    private void glideStart(ClientPlayerEntity self, LivingEntity target) {
        if (self.isGliding()) {
            enter(State.CLIMB);
            return;
        }
        if (!GearSlots.wearingUsableElytra(self)) {
            enter(State.PREFLIGHT);
            return;
        }
        if (self.isOnGround() && stateTicks > 2) {
            enter(State.PREFLIGHT);
            return;
        }
        if (stateTicks > 80) {
            fail("glide never started");
            return;
        }
        if (target != null) {
            steer(MathUtil.yawTo(self.getEyePos(), MathUtil.aimPoint(target)), -10.0);
        }
        // A fresh key edge is needed to start gliding: never press on two consecutive ticks.
        if (!self.isOnGround() && !InputOverride.jumpedLastTick()
                && (self.getVelocity().y < 0.0 || stateTicks >= 3)) {
            InputOverride.requestJump();
        }
    }

    // ------------------------------------------------------------------
    // CLIMB: rockets toward dive altitude
    // ------------------------------------------------------------------

    private void climb(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, LivingEntity target) {
        if (!self.isGliding()) {
            enter(self.isOnGround() ? State.PREFLIGHT : State.GLIDE_START);
            return;
        }
        if (target == null) {
            enter(State.PREFLIGHT);
            return;
        }
        if (!GearSlots.wearingUsableElytra(self)) {
            enter(State.PREFLIGHT);
            return;
        }
        double alt = self.getY() - target.getY();
        double horiz = horizontal(self, target);
        double speed = self.getVelocity().length();
        double needed = climbHeight.get() + extraClimb;
        boolean high = alt >= needed;
        double clearance = DamageCalculator.distanceToGround(world, self);

        // Lead the dive start by how far the target drifts while we fall (~alt / 2 ticks).
        Vec3d lead = target.getEntityPos().add(targetVel.multiply(MathHelper.clamp(alt / 2.0, 0.0, 15.0)));
        double leadHoriz = Math.hypot(lead.x - self.getX(), lead.z - self.getZ());
        if (high && leadHoriz <= diveRadius.get() + Math.min(4.0, speed * 2.0)
                && clearance >= groundClear.get() && !target.isGliding()) {
            losLost = 0;
            enter(State.DIVE);
            return;
        }

        Vec3d eye = self.getEyePos();
        double yaw = MathUtil.yawTo(eye, new Vec3d(lead.x, eye.y, lead.z));
        // Below altitude: steep climb. At altitude: cruise level toward the overhead point
        // (a slight nose-up keeps altitude without rockets once the speed is up).
        double pitch = high ? (alt > needed + 6.0 ? 4.0 : -4.0) : -climbPitch.get();
        if (high && horiz > maxHoriz.get() * 2.5) {
            pitch = -8.0; // far away: cover ground before worrying about the overhead point
        }
        if (blockedAhead(self, speed)) {
            pitch = -75.0;
        }
        steer(yaw, pitch);
        if (!high || speed < climbSpeed.get()) {
            tryRocket(client, self, world);
        }
        slamPending = high && leadHoriz < diveRadius.get() * 3.0;
    }

    // ------------------------------------------------------------------
    // DIVE: track the lead point, decide when to take the elytra off
    // ------------------------------------------------------------------

    private void dive(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, LivingEntity target) {
        if (!self.isGliding()) {
            enter(self.isOnGround() ? State.PREFLIGHT : State.REGLIDE);
            return;
        }
        if (target == null) {
            enter(State.PREFLIGHT);
            return;
        }
        slamPending = true;
        Vec3d eye = self.getEyePos();
        Vec3d aim = leadPoint(self, target);
        double dist = eye.distanceTo(aim);
        double speed = self.getVelocity().length();
        double alt = self.getY() - target.getY();
        double horiz = Math.hypot(aim.x - eye.x, aim.z - eye.z);
        // Always steep: vanilla clamps fallDistance to 1 while gliding slower than 0.5 b/t
        // downward, so a shallow glide never banks a smash. Nose-down >= minDivePitch keeps
        // vy well below -0.5; the yaw still tracks the lead point for the horizontal error.
        double pitch = MathHelper.clamp(MathUtil.pitchTo(eye, aim), minDivePitch.get(), 89.0);
        steer(MathUtil.yawTo(eye, aim), pitch);

        // Target took off (elytra): a smash dive onto a flier cannot be timed. Hold altitude.
        if (target.isGliding()) {
            reposition();
            return;
        }
        // Overshoot: the target is now further sideways than a steep dive can correct.
        if (horiz > Math.max(6.0, alt * 0.9) && alt > 4.0) {
            reposition();
            return;
        }
        // Missed: already level with (or below) the target without a strike.
        if (alt < 0.5) {
            reposition();
            return;
        }
        // Terrain guard: only real obstacles count - ground under us that sits clearly ABOVE
        // the target's feet (a ledge/roof). Ground at target level is the expected impact.
        double clearance = DamageCalculator.distanceToGround(world, self);
        if (clearance != Double.MAX_VALUE) {
            double groundTop = self.getY() - clearance;
            if (groundTop > target.getY() + 2.5 && clearance < 4.0) {
                abortDive();
                return;
            }
        }
        losLost = RaycastValidator.hitboxVisible(eye, target.getBoundingBox(), self) ? 0 : losLost + 1;
        if (losLost >= 10) {
            abortDive();
            return;
        }
        if (dist > 14.0 && rocketInDive.get() && speed < diveSpeed.get()) {
            tryRocket(client, self, world);
        }
        if (swapAllowed(self, target) && impactWindowOpen(self, target)) {
            enter(State.SWAP_OFF);
        }
    }

    /** Overshoot / miss: climb back over the target without counting it as a hard failure. */
    private void reposition() {
        losLost = 0;
        enter(self() != null && self().isGliding() ? State.CLIMB : State.REGLIDE);
    }

    private static ClientPlayerEntity self() {
        return MinecraftClient.getInstance().player;
    }

    private void abortDive() {
        extraClimb += 4.0;
        if (++diveAborts >= 4) {
            abort("dive keeps failing (obstacles / ground)");
        } else {
            enter(State.CLIMB);
        }
    }

    /** Gate for taking the elytra off: strike gear present, shield, projected fall distance. */
    private boolean swapAllowed(ClientPlayerEntity self, LivingEntity target) {
        if (maceSlot(self) == -1 || GearSlots.counterpartHotbarSlot(self) == -1) {
            return false;
        }
        if (respectShield.get() && CombatCoordinator.shieldBlocks(target, self)
                && !CombatCoordinator.shieldBreakerWants(MinecraftClient.getInstance(), target)) {
            return false;
        }
        // Fall distance is held at ~1 while gliding above -0.5 b/t: only a steep dive banks enough.
        double vy = self.getVelocity().y;
        double projected = self.fallDistance + Math.max(0.0, -vy) * (swapLead.get() + 2);
        return projected >= minFall.get() * 0.8;
    }

    /**
     * Forward simulation of the post-swap flight (vanilla air physics: vy = (vy - 0.08) * 0.98,
     * horizontal drag 0.91) against the target's tracked motion. True when the target hitbox is
     * inside reach at some tick of the strike window [swapLead, swapLead + 2].
     */
    private boolean impactWindowOpen(ClientPlayerEntity self, LivingEntity target) {
        Vec3d pos = self.getEntityPos();
        Vec3d v = self.getVelocity();
        Box base = target.getBoundingBox();
        double r = reach.get();
        double r2 = r * r;
        int delay = swapLead.get().intValue();
        // Earliest tick the post-swap fall puts the target in reach. Swap when that is at
        // most lead+2 ticks away; at fast dive speeds the reach sphere is crossed in 1-2
        // ticks, so requiring it exactly inside [lead, lead+2] skipped most passes.
        for (int t = 1; t <= delay + 2; t++) {
            Vec3d prev = pos;
            pos = pos.add(v);
            v = new Vec3d(v.x * 0.91, (v.y - 0.08) * 0.98, v.z * 0.91);
            Box tb = base.offset(targetVel.multiply(t));
            // Sample the tick's path (4 sub-steps) so a fast pass through the sphere counts.
            for (int k = 1; k <= 4; k++) {
                Vec3d eye = prev.lerp(pos, k / 4.0).add(0.0, STANDING_EYE, 0.0);
                if (tb.squaredMagnitude(eye) <= r2) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // SWAP_OFF / STRIKE / SWAP_ON
    // ------------------------------------------------------------------

    private void swapOff(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        slamPending = true;
        aimAt(self, target);
        if (!GearSlots.wearingElytra(self)) {
            swapTick = TickPhase.tickId();
            enter(State.STRIKE);
            return;
        }
        if (stateTicks > 5) {
            enter(State.DIVE);
            return;
        }
        int h = GearSlots.counterpartHotbarSlot(self);
        if (h == -1) {
            abort("chestplate left the hotbar");
            return;
        }
        if (!acted() && GearSlots.swapWorn(client, h)) {
            noteAct();
            chestplateOn = true;
            swapTick = TickPhase.tickId();
            enter(State.STRIKE);
        }
    }

    private void strike(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        long elapsed = TickPhase.tickId() - swapTick;
        if (target == null || !target.isAlive()) {
            enter(State.SWAP_ON);
            return;
        }
        slamPending = true;
        aimAt(self, target);
        if (GearSlots.wearingElytra(self)) {
            enter(State.SWAP_OFF); // the swap did not take
            return;
        }
        if (elapsed > strikeWindow.get() || self.getY() < target.getY() - 0.5) {
            enter(State.SWAP_ON); // missed: recover instead of falling with the chestplate on
            return;
        }
        if (elapsed < swapLead.get() || self.isGliding() || acted()) {
            return;
        }
        Vec3d eye = self.getEyePos();
        double r = reach.get();
        if (target.getBoundingBox().squaredMagnitude(eye) > r * r
                || self.fallDistance < minFall.get()
                || target.hurtTime > maxHurt.get().intValue()
                || (respectShield.get() && CombatCoordinator.shieldBlocks(target, self))) {
            return;
        }
        Vec3d aim = MathUtil.bestAimPoint(self, target, RotationManager.currentYaw(), self.getPitch());
        if (!RotationManager.isAimedAt(aim, 14.0) || !RaycastValidator.canSee(eye, aim, self)) {
            return;
        }
        int mace = maceSlot(self);
        if (mace == -1) {
            abort("mace left the hotbar");
            return;
        }
        if (CombatActions.attributeSwapAttack(client, mace, target, backTicks.get().intValue())) {
            noteAct();
            strikes++;
            strikeTick = TickPhase.tickId();
            diveAborts = 0;
            extraClimb = 0.0;
            enter(State.SWAP_ON);
        }
    }

    private void swapOn(MinecraftClient client, ClientPlayerEntity self) {
        if (GearSlots.wearingUsableElytra(self)) {
            chestplateOn = false;
            enter(State.REGLIDE);
            return;
        }
        if (stateTicks > 40) {
            abort("cannot re-equip the elytra");
            return;
        }
        if (TickPhase.tickId() <= strikeTick || acted()) {
            return; // never in the tick of the attack
        }
        int h = GearSlots.counterpartHotbarSlot(self);
        if (h == -1) {
            abort("elytra left the hotbar");
            return;
        }
        if (GearSlots.swapWorn(client, h)) {
            noteAct();
        }
    }

    private void reglide(ClientPlayerEntity self, LivingEntity target) {
        if (self.isGliding()) {
            enter(State.CLIMB);
            return;
        }
        if (self.isOnGround()) {
            enter(State.PREFLIGHT); // no Wind Burst launch left: take off again from the ground
            return;
        }
        if (stateTicks > 80) {
            fail("could not resume gliding");
            return;
        }
        if (target != null) {
            steer(MathUtil.yawTo(self.getEyePos(), MathUtil.aimPoint(target)), -10.0);
        }
        if (GearSlots.wearingUsableElytra(self) && !InputOverride.jumpedLastTick()
                && (self.getVelocity().y < 0.0 || stateTicks >= 8)) {
            InputOverride.requestJump();
        }
    }

    private void aborting(MinecraftClient client, ClientPlayerEntity self) {
        if (!GearSlots.wearingElytra(self) && !self.isOnGround() && stateTicks <= 20) {
            int h = GearSlots.counterpartHotbarSlot(self);
            if (h != -1 && !acted() && GearSlots.swapWorn(client, h)) {
                noteAct();
                chestplateOn = false;
            }
            return; // try again next tick until the elytra is on or the grace period ends
        }
        self.sendMessage(Text.literal("[Zephyr] ElytraMaceBot: " + abortReason), true);
        chestplateOn = false;
        setEnabled(false);
    }

    // ------------------------------------------------------------------
    // Rockets
    // ------------------------------------------------------------------

    private void tryRocket(MinecraftClient client, ClientPlayerEntity self, ClientWorld world) {
        if (!self.isGliding() || acted() || sinceRocket < rocketGap.get() || SwapManager.pending()) {
            return;
        }
        int slot = rocketSlot(self);
        if (slot == -1) {
            abort("out of firework rockets");
            return;
        }
        if (self.getInventory().getSelectedSlot() != slot) {
            if (SwapManager.select(client, slot)) {
                noteAct(); // the use itself waits for the next tick
            }
            return;
        }
        if (ItemUtil.coolingDown(self, self.getMainHandStack()) || rocketActive(world, self)) {
            return;
        }
        if (CombatActions.interactItem(client).isAccepted()) {
            noteAct();
            sinceRocket = 0;
            rockets++;
        }
    }

    private boolean rocketActive(ClientWorld world, ClientPlayerEntity self) {
        return !world.getEntitiesByClass(FireworkRocketEntity.class, self.getBoundingBox().expand(6.0),
                r -> r.getOwner() == self && !r.isRemoved()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    private static double horizontal(ClientPlayerEntity self, LivingEntity target) {
        return Math.hypot(target.getX() - self.getX(), target.getZ() - self.getZ());
    }

    /** True when a straight line along the flight direction hits a block within ~7 ticks. */
    private boolean blockedAhead(ClientPlayerEntity self, double speed) {
        Vec3d eye = self.getEyePos();
        Vec3d dir = speed > 0.3 ? self.getVelocity().normalize() : self.getRotationVector();
        double dist = Math.max(8.0, speed * 7.0);
        return !RaycastValidator.canSee(eye, eye.add(dir.multiply(dist)), self);
    }

    /** Aim point advanced by the target's tracked motion over the time-to-impact. */
    private Vec3d leadPoint(ClientPlayerEntity self, LivingEntity target) {
        Vec3d base = MathUtil.aimPoint(target);
        double speed = Math.max(0.6, self.getVelocity().length());
        double t = MathHelper.clamp(self.getEyePos().distanceTo(base) / speed, 0.0, 12.0);
        return base.add(targetVel.multiply(t));
    }

    private void aimAt(ClientPlayerEntity self, LivingEntity target) {
        if (target == null) {
            return;
        }
        Vec3d aim = MathUtil.bestAimPoint(self, target, RotationManager.currentYaw(), self.getPitch());
        Vec3d eye = self.getEyePos();
        steer(MathUtil.yawTo(eye, aim), MathUtil.pitchTo(eye, aim));
    }

    private void steer(double yaw, double pitch) {
        RotationManager.request(RotationRequest.to(yaw, MathHelper.clamp(pitch, -90.0, 90.0))
                .speed(rotSpeedMin.get(), rotSpeedMax.get())
                .jitter(jitter.get())
                .silent(false) // flight physics follow the real view: client and server must agree
                .priority(PRIORITY)
                .validWhile(this::isEnabled)
                .expiresAfter(3)
                .build());
    }

    /** Per-tick velocity estimate of the target (smoothed; teleports are ignored). */
    private void trackTarget(LivingEntity target) {
        if (target == null) {
            trackedId = -1;
            lastTargetPos = null;
            targetVel = Vec3d.ZERO;
            return;
        }
        Vec3d p = target.getEntityPos();
        if (target.getId() == trackedId && lastTargetPos != null) {
            Vec3d d = p.subtract(lastTargetPos);
            targetVel = d.length() > 1.5 ? Vec3d.ZERO : targetVel.multiply(0.5).add(d.multiply(0.5));
        } else {
            targetVel = Vec3d.ZERO;
        }
        trackedId = target.getId();
        lastTargetPos = p;
    }

    @Override
    public String hudLabel() {
        return name + " · " + state.name().toLowerCase() + " · " + strikes + "/" + rockets;
    }
}
