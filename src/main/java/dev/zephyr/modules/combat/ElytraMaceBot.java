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
import dev.zephyr.core.target.TargetLock;
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
 * ElytraMaceBot - fully automatic elytra + mace dive loop (rewritten).
 *
 * <pre>
 *  IDLE -> EQUIP -> TAKEOFF -> CLIMB -> APPROACH -> DIVE -> DROP (elytra off, strike) -> RECOVER
 *                                ^                                                        |
 *                                +--------------------------------------------------------+
 * </pre>
 *
 * <p><b>Why the old loop kept climbing back up.</b> It flew until it was directly over the
 * target and then pointed the nose down. An elytra keeps its horizontal speed (1.5-2 b/t at
 * cruise), so the player sailed past the target during the dive, the overshoot check fired and
 * the bot climbed again - forever. This version flies the dive as a straight LINE from a runway:
 * <ul>
 *   <li><b>CLIMB</b> gains altitude while moving out to a runway distance (about the cruise
 *       height away horizontally), so the dive can start from the side, not from overhead.</li>
 *   <li><b>APPROACH</b> holds cruise altitude flying at the target and starts the dive only when
 *       the line to the target is steep enough ({@code diveAngle}) AND the momentum already
 *       points at the target.</li>
 *   <li><b>DIVE</b> steers the VELOCITY onto the line, not just the nose: the look vector is
 *       {@code d*(1+k) - v*k} (d = unit vector to the lead point, v = unit velocity), the
 *       classic velocity-pursuit correction. Too fast? the nose is raised a little, which bleeds
 *       speed into lift.</li>
 *   <li><b>DROP</b>: vanilla only grants the mace smash bonus to a NON-gliding attacker with
 *       {@code fallDistance > 1.5}, so the elytra is swapped for the chestplate a couple of ticks
 *       before reach (the impact is predicted with vanilla free-fall physics), then the hit goes
 *       out through {@link CombatActions#attributeSwapAttack}.</li>
 *   <li><b>RECOVER</b>: elytra back on, glide restarted with a real jump key edge, back to
 *       CLIMB. A miss recovers immediately instead of falling with the chestplate on.</li>
 * </ul>
 * All actions run in the pre-movement stage; one module action per tick (rocket, chest click or
 * attack never share a tick); rotations are visible (elytra physics follow the real view).
 */
public final class ElytraMaceBot extends Module {

    private static final int PRIORITY = 5;
    private static final double EYE = 1.62;

    // --- targeting
    private final NumberSetting range = num("range", "Target range", 16, 128, 2, 64);
    private final BooleanSetting groundedOnly = bool("groundedOnly", "Only target players not flying", true);
    // --- flight
    private final NumberSetting cruiseHeight = num("cruiseHeight", "Cruise height above target", 12, 60, 1, 26);
    private final NumberSetting climbPitch = num("climbPitch", "Climb pitch (deg up)", 25, 75, 1, 45);
    private final NumberSetting diveAngle = num("diveAngle", "Start dive at line angle (deg)", 35, 85, 1, 55);
    private final NumberSetting minDivePitch = num("minDivePitch", "Min dive pitch (deg down)", 15, 80, 1, 30);
    private final NumberSetting navGain = num("navGain", "Dive steering gain", 0.0, 3.0, 0.1, 1.2);
    private final NumberSetting maxDiveSpeed = num("maxDiveSpeed", "Max dive speed (b/t)", 1.0, 4.0, 0.1, 2.6);
    private final NumberSetting cruiseSpeed = num("cruiseSpeed", "Rocket below speed (b/t)", 0.5, 2.5, 0.05, 1.2);
    private final NumberSetting rocketGap = num("rocketGap", "Min ticks between rockets", 5, 40, 1, 12);
    private final NumberSetting groundClear = num("groundClear", "Min ground clearance", 2, 20, 1, 4);
    // --- strike
    private final NumberSetting minFall = num("minFall", "Min fall distance to smash", 2, 20, 0.5, 4);
    private final NumberSetting reach = num("reach", "Strike reach", 2.5, 3.0, 0.05, 2.95);
    private final NumberSetting swapLead = num("swapLead", "Chest swap -> strike (ticks)", 1, 5, 1, 2);
    private final NumberSetting strikeWindow = num("strikeWindow", "Strike window (ticks)", 3, 15, 1, 8);
    private final NumberSetting maxHurt = num("hurtTime", "Max target hurt time", 0, 10, 1, 6);
    private final NumberSetting backTicks = num("backTicks", "Swap-back ticks", 1, 10, 1, 2);
    private final BooleanSetting requireWindBurst = bool("windBurst", "Require Wind Burst mace", true);
    private final BooleanSetting respectShield = bool("respectShield", "Hold strike into raised shield", true);
    // --- safety / feel
    private final NumberSetting minHealth = num("minHealth", "Abort below health", 2, 20, 1, 8);
    private final NumberSetting rotSpeedMin = num("rotMin", "Rotation min deg/t", 10, 180, 5, 45);
    private final NumberSetting rotSpeedMax = num("rotMax", "Rotation max deg/t", 10, 180, 5, 110);
    private final NumberSetting jitter = num("jitter", "Rotation jitter", 0.0, 2.0, 0.05, 0.15);

    private enum State { IDLE, EQUIP, TAKEOFF, CLIMB, APPROACH, DIVE, DROP, RECOVER, ABORT }

    private final TargetLock lock = new TargetLock();
    private State state = State.IDLE;
    private int stateTicks;
    private int sinceRocket = 99;
    private int misses;
    private int failures;
    private int losLost;
    private long actTick = Long.MIN_VALUE;
    private long swapTick = Long.MIN_VALUE;
    private long strikeTick = Long.MIN_VALUE;
    private boolean chestplateOn;
    private boolean swapSent;
    /** Chest swaps attempted on the current dive (a rejected click must not loop forever). */
    private int swapAttempts;
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

    /** CombatCoordinator: other hitters hold off while a dive on our target is under way. */
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
        rockets = 0;
    }

    @Override
    protected void onDisable() {
        RotationManager.clear(PRIORITY);
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self != null && chestplateOn && !GearSlots.wearingElytra(self) && !self.isOnGround()) {
            int h = GearSlots.counterpartHotbarSlot(self);
            if (h == -1 || !GearSlots.swapWorn(client, h)) {
                self.sendMessage(Text.literal("[Zephyr] ElytraMaceBot stopped mid-air with the chestplate on - swap the elytra back!"), true);
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
        misses = 0;
        failures = 0;
        losLost = 0;
        chestplateOn = false;
        swapSent = false;
        slamPending = false;
        trackedId = -1;
        lastTargetPos = null;
        targetVel = Vec3d.ZERO;
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
        if (++failures >= 4) {
            abort(reason);
        } else {
            enter(State.EQUIP);
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
            reset();
            return;
        }
        stateTicks++;
        sinceRocket++;
        slamPending = false;

        LivingEntity target = lock.update(client, range.get(), groundedOnly.get());
        trackTarget(target);

        boolean recovering = state == State.RECOVER || state == State.ABORT;
        if (!recovering && self.getHealth() + self.getAbsorptionAmount() < minHealth.get()) {
            abort("health below limit");
        }

        switch (state) {
            case IDLE -> idle(self, target);
            case EQUIP -> equip(client, self, target);
            case TAKEOFF -> takeoff(self, target);
            case CLIMB -> climb(client, self, world, target);
            case APPROACH -> approach(client, self, world, target);
            case DIVE -> dive(client, self, world, target);
            case DROP -> drop(client, self, target);
            case RECOVER -> recover(client, self, target);
            case ABORT -> aborting(client, self);
        }
    }

    // ------------------------------------------------------------------
    // IDLE / EQUIP / TAKEOFF
    // ------------------------------------------------------------------

    private void idle(ClientPlayerEntity self, LivingEntity target) {
        if (chestplateOn && !self.isOnGround() && !GearSlots.wearingElytra(self)) {
            enter(State.RECOVER); // never idle in the air with the chestplate on
            return;
        }
        if (target != null) {
            enter(State.EQUIP);
            return;
        }
        if (self.isGliding()) {
            // No target while airborne: keep flying level instead of nosediving into the ground.
            steer(self.getYaw(), -5.0);
        }
    }

    private void equip(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (target == null) {
            enter(State.IDLE);
            return;
        }
        if (stateTicks > 200) {
            abort("could not prepare gear (inventory busy?)");
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
        enter(self.isGliding() ? State.CLIMB : State.TAKEOFF);
    }

    /** True when the item is in the hotbar; otherwise stages it from the inventory (or aborts). */
    private boolean ready(MinecraftClient client, ClientPlayerEntity self, Predicate<ItemStack> pred, String what) {
        if (InventoryManager.findHotbarSlot(self, pred) != -1) {
            return true;
        }
        if (!GearSlots.has(self, pred)) {
            abort("no " + what);
            return false;
        }
        if (!acted() && GearSlots.stageToHotbar(client, pred, maceSlot(self), rocketSlot(self),
                GearSlots.counterpartHotbarSlot(self))) {
            noteAct();
        }
        return false;
    }

    private void takeoff(ClientPlayerEntity self, LivingEntity target) {
        if (self.isGliding()) {
            enter(State.CLIMB);
            return;
        }
        if (!GearSlots.wearingUsableElytra(self)) {
            enter(State.EQUIP);
            return;
        }
        if (stateTicks > 60) {
            fail("glide never started (no headroom?)");
            return;
        }
        if (target != null) {
            steer(MathUtil.yawTo(self.getEyePos(), target.getEntityPos()), -20.0);
        }
        if (self.isOnGround()) {
            if (!InputOverride.jumpedLastTick()) {
                InputOverride.requestJump();
            }
            return;
        }
        // Starting a glide needs a fresh jump key edge in the air: press on every other tick.
        if (!InputOverride.jumpedLastTick() && (self.getVelocity().y < 0.15 || stateTicks >= 4)) {
            InputOverride.requestJump();
        }
    }

    // ------------------------------------------------------------------
    // CLIMB: altitude + runway
    // ------------------------------------------------------------------

    private void climb(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, LivingEntity target) {
        if (!self.isGliding()) {
            enter(self.isOnGround() ? State.EQUIP : State.RECOVER);
            return;
        }
        if (target == null) {
            enter(State.IDLE);
            return;
        }
        double alt = self.getY() - target.getY();
        double horiz = horizontal(self, target);
        double speed = self.getVelocity().length();
        double runway = cruiseHeight.get() * 0.9;

        if (alt >= cruiseHeight.get()) {
            enter(State.APPROACH);
            return;
        }
        if (stateTicks > 400) {
            miss(); // never reached altitude (ceiling / no rockets working)
            return;
        }
        Vec3d eye = self.getEyePos();
        double towards = MathUtil.yawTo(eye, target.getEntityPos());
        // Too close to start a clean line later: climb AWAY from the target to build a runway.
        double yaw = horiz < runway * 0.6 ? towards + 180.0 : towards;
        double pitch = -climbPitch.get();
        if (blockedAhead(self, speed)) {
            pitch = -75.0;
        }
        steer(yaw, pitch);
        if (speed < 1.6 || self.getVelocity().y < 0.3) {
            tryRocket(client, self, world);
        }
    }

    // ------------------------------------------------------------------
    // APPROACH: hold altitude, wait for a steep, aligned dive line
    // ------------------------------------------------------------------

    private void approach(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, LivingEntity target) {
        if (!self.isGliding()) {
            enter(self.isOnGround() ? State.EQUIP : State.RECOVER);
            return;
        }
        if (target == null) {
            enter(State.IDLE);
            return;
        }
        Vec3d eye = self.getEyePos();
        Vec3d aim = leadPoint(self, target);
        double alt = eye.y - aim.y;
        double dx = aim.x - eye.x, dz = aim.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        Vec3d v = self.getVelocity();
        double hSpeed = Math.sqrt(v.x * v.x + v.z * v.z);
        double heading = hSpeed < 1.0e-3 || horiz < 1.0e-3 ? 1.0 : (v.x * dx + v.z * dz) / (hSpeed * horiz);
        double lineAngle = Math.toDegrees(Math.atan2(alt, Math.max(horiz, 1.0e-3)));
        slamPending = !target.isGliding() && lineAngle > diveAngle.get() - 15.0;
        if (stateTicks > 400) {
            miss(); // circling without ever getting a clean line (or the target keeps flying)
            return;
        }

        if (alt < cruiseHeight.get() * 0.5) {
            enter(State.CLIMB); // lost too much height on the way in
            return;
        }
        if (!target.isGliding()
                && ((lineAngle >= diveAngle.get() && (heading > 0.6 || hSpeed < 0.4)) || lineAngle >= 82.0)) {
            losLost = 0;
            swapAttempts = 0;
            enter(State.DIVE);
            return;
        }
        // Passed over without an aligned line: go back out for another runway.
        if (lineAngle >= diveAngle.get() && heading < -0.2 && horiz < cruiseHeight.get() * 0.5) {
            enter(State.CLIMB);
            return;
        }
        // Altitude hold: nose up when low, level/slightly down when high.
        double altErr = cruiseHeight.get() - alt;
        double pitch = MathHelper.clamp(-altErr * 2.0 - 2.0, -30.0, 12.0);
        if (blockedAhead(self, v.length())) {
            pitch = -60.0;
        }
        steer(MathUtil.yawTo(eye, aim), pitch);
        if (v.length() < cruiseSpeed.get() || altErr > 6.0) {
            tryRocket(client, self, world);
        }
    }

    // ------------------------------------------------------------------
    // DIVE: velocity-pursuit onto the target line
    // ------------------------------------------------------------------

    private void dive(MinecraftClient client, ClientPlayerEntity self, ClientWorld world, LivingEntity target) {
        if (!self.isGliding()) {
            enter(self.isOnGround() ? State.EQUIP : State.RECOVER);
            return;
        }
        if (target == null) {
            enter(State.IDLE);
            return;
        }
        if (target.isGliding()) {
            enter(State.APPROACH); // the target took off: hold altitude and wait
            return;
        }
        slamPending = true;
        Vec3d eye = self.getEyePos();
        Vec3d aim = leadPoint(self, target);
        Vec3d to = aim.subtract(eye);
        double dist = to.length();
        Vec3d d = to.multiply(1.0 / Math.max(dist, 1.0e-6));
        Vec3d v = self.getVelocity();
        double speed = v.length();
        Vec3d vh = speed > 0.3 ? v.multiply(1.0 / speed) : self.getRotationVector();

        // Velocity pursuit: aim past the target on the side the velocity is drifting from, so the
        // flight path (not just the nose) converges onto the line to the target.
        double k = navGain.get();
        Vec3d cmd = d.multiply(1.0 + k).subtract(vh.multiply(k));
        double ch = Math.sqrt(cmd.x * cmd.x + cmd.z * cmd.z);
        double yaw = Math.toDegrees(Math.atan2(cmd.z, cmd.x)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(cmd.y, ch));
        if (speed > maxDiveSpeed.get()) {
            pitch -= 12.0; // raise the nose a little: lift bleeds off speed
        }
        pitch = MathHelper.clamp(pitch, minDivePitch.get(), 89.0);
        steer(yaw, pitch);

        // Missed / overshot: below the target's head, or the target fell behind the flight path.
        double alongPath = vh.dotProduct(d);
        Box tb = target.getBoundingBox();
        if (eye.y < tb.maxY - 0.2 || (alongPath < 0.2 && dist > 4.0)) {
            miss();
            return;
        }
        // Terrain: ground under us that sits clearly ABOVE the target's feet (a roof/ledge),
        // close enough to hit within a couple of ticks at this speed.
        double clearance = DamageCalculator.distanceToGround(world, self);
        if (clearance != Double.MAX_VALUE) {
            double groundTop = self.getY() - clearance;
            if (groundTop > target.getY() + 2.0 && clearance < groundClear.get() + speed * 2.0) {
                miss();
                return;
            }
        }
        losLost = RaycastValidator.hitboxVisible(eye, tb, self) ? 0 : losLost + 1;
        if (losLost >= 8) {
            miss();
            return;
        }
        if (dist > 18.0 && speed < 1.2) {
            tryRocket(client, self, world);
        }
        if (swapReady(self, target) && impactSoon(self, target)) {
            if (swapAttempts >= 2) {
                miss(); // the chest click keeps being rejected: climb and retry the whole dive
                return;
            }
            swapSent = false;
            enter(State.DROP);
            drop(client, self, target); // send the chest swap this tick
        }
    }

    /** @return true when this miss aborted the module (caller must not change state). */
    private boolean miss() {
        losLost = 0;
        if (++misses >= 8) {
            abort("dive keeps missing (obstacles / target dodging)");
            return true;
        }
        enter(State.CLIMB);
        return false;
    }

    /** Gear present, shield not raised at us, enough fall banked for a smash after the swap. */
    private boolean swapReady(ClientPlayerEntity self, LivingEntity target) {
        if (maceSlot(self) == -1 || GearSlots.counterpartHotbarSlot(self) == -1) {
            return false;
        }
        if (respectShield.get() && CombatCoordinator.shieldBlocks(target, self)
                && !CombatCoordinator.shieldBreakerWants(MinecraftClient.getInstance(), target)) {
            return false;
        }
        // Gliding clamps fallDistance to 1 while vy > -0.5; the steep dive banks it normally.
        double vy = self.getVelocity().y;
        double projected = self.fallDistance + Math.max(0.0, -vy) * (swapLead.get() + 1);
        return projected >= minFall.get();
    }

    /**
     * Free-fall forecast after the swap (vanilla: pos += v; vy = (vy - 0.08) * 0.98; vxz *= 0.91)
     * against the target's tracked motion: true when the target hitbox comes inside reach within
     * lead+2 ticks. Each tick's path is sub-sampled so a fast pass through the reach sphere counts.
     */
    private boolean impactSoon(ClientPlayerEntity self, LivingEntity target) {
        double px = self.getX(), py = self.getY(), pz = self.getZ();
        Vec3d v = self.getVelocity();
        double vx = v.x, vy = v.y, vz = v.z;
        Box base = target.getBoundingBox();
        double r2 = reach.get() * reach.get();
        int horizon = swapLead.get().intValue() + 2;
        for (int t = 1; t <= horizon; t++) {
            double nx = px + vx, ny = py + vy, nz = pz + vz;
            Box tb = base.offset(targetVel.x * t, targetVel.y * t, targetVel.z * t);
            for (int s = 1; s <= 4; s++) {
                double f = s / 4.0;
                Vec3d eye = new Vec3d(px + (nx - px) * f, py + (ny - py) * f + EYE, pz + (nz - pz) * f);
                if (tb.squaredMagnitude(eye) <= r2) {
                    return true;
                }
            }
            px = nx;
            py = ny;
            pz = nz;
            vx *= 0.91;
            vy = (vy - 0.08) * 0.98;
            vz *= 0.91;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // DROP: elytra off, strike
    // ------------------------------------------------------------------

    private void drop(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            enter(State.RECOVER);
            return;
        }
        aimAt(self, target);
        if (GearSlots.wearingElytra(self)) {
            if (swapSent && stateTicks > 3) {
                swapAttempts++;
                enter(State.DIVE); // the click did not take: keep diving, try again (capped)
                return;
            }
            if (!swapSent && stateTicks > 3) {
                miss(); // the click could not even be sent (screen open / eating / busy)
                return;
            }
            slamPending = true;
            if (!swapSent) {
                int h = GearSlots.counterpartHotbarSlot(self);
                if (h == -1) {
                    abort("chestplate left the hotbar");
                    return;
                }
                if (!acted() && GearSlots.swapWorn(client, h)) {
                    noteAct();
                    swapSent = true;
                    chestplateOn = true;
                    swapTick = TickPhase.tickId();
                }
            }
            return;
        }
        slamPending = true;
        long elapsed = TickPhase.tickId() - swapTick;
        Box tb = target.getBoundingBox();
        Vec3d eye = self.getEyePos();
        if (elapsed > strikeWindow.get() || eye.y < tb.minY + 0.5) {
            if (!miss()) {
                enter(State.RECOVER); // missed: elytra back on right away, never fall with the chestplate
            }
            return;
        }
        // The server must have dropped gliding (it does on its tick after the click) before
        // the hit, or the smash bonus is not applied.
        if (elapsed < swapLead.get() || self.isGliding() || acted()) {
            return;
        }
        double r = reach.get();
        if (tb.squaredMagnitude(eye) > r * r
                || self.fallDistance < minFall.get()
                || target.hurtTime > maxHurt.get().intValue()
                || (respectShield.get() && CombatCoordinator.shieldBlocks(target, self))) {
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
            misses = 0;
            failures = 0;
            strikeTick = TickPhase.tickId();
            enter(State.RECOVER);
        }
    }

    // ------------------------------------------------------------------
    // RECOVER: elytra on, glide again
    // ------------------------------------------------------------------

    private void recover(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (!GearSlots.wearingElytra(self)) {
            if (stateTicks > 40) {
                abort("cannot re-equip the elytra");
                return;
            }
            // Never in the attack tick; one click per tick.
            if (TickPhase.tickId() > strikeTick && !acted()) {
                int h = GearSlots.counterpartHotbarSlot(self);
                if (h == -1) {
                    abort("elytra left the hotbar");
                    return;
                }
                if (GearSlots.swapWorn(client, h)) {
                    noteAct();
                    chestplateOn = false;
                }
            }
            return;
        }
        chestplateOn = false;
        if (self.isGliding()) {
            enter(State.CLIMB);
            return;
        }
        if (self.isOnGround()) {
            enter(State.EQUIP);
            return;
        }
        if (stateTicks > 80) {
            fail("could not resume gliding");
            return;
        }
        if (target != null) {
            steer(MathUtil.yawTo(self.getEyePos(), target.getEntityPos()) + 180.0, -25.0);
        }
        if (GearSlots.wearingUsableElytra(self) && !InputOverride.jumpedLastTick()
                && (self.getVelocity().y < 0.0 || stateTicks >= 6)) {
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
            return;
        }
        if (GearSlots.wearingElytra(self)) {
            chestplateOn = false;
        }
        self.sendMessage(Text.literal("[Zephyr] ElytraMaceBot: " + abortReason), true);
        setEnabled(false); // onDisable still warns if we are airborne with the chestplate on
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
                noteAct(); // the use itself goes out next tick
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

    /** A block along the flight direction within ~7 ticks of travel. */
    private boolean blockedAhead(ClientPlayerEntity self, double speed) {
        Vec3d eye = self.getEyePos();
        Vec3d dir = speed > 0.3 ? self.getVelocity().normalize() : self.getRotationVector();
        double dist = Math.max(8.0, speed * 7.0);
        return !RaycastValidator.canSee(eye, eye.add(dir.multiply(dist)), self);
    }

    /** The target's head (where a falling attacker meets it), advanced by its motion over the time to impact. */
    private Vec3d leadPoint(ClientPlayerEntity self, LivingEntity target) {
        Box b = target.getBoundingBox();
        Vec3d head = new Vec3d((b.minX + b.maxX) * 0.5, b.maxY - 0.2, (b.minZ + b.maxZ) * 0.5);
        double speed = Math.max(0.6, self.getVelocity().length());
        double t = MathHelper.clamp(self.getEyePos().distanceTo(head) / speed, 0.0, 15.0);
        return head.add(targetVel.multiply(t));
    }

    private void aimAt(ClientPlayerEntity self, LivingEntity target) {
        Vec3d aim = MathUtil.bestAimPoint(self, target, RotationManager.currentYaw(), self.getPitch());
        Vec3d eye = self.getEyePos();
        steer(MathUtil.yawTo(eye, aim), MathUtil.pitchTo(eye, aim));
    }

    private void steer(double yaw, double pitch) {
        RotationManager.request(RotationRequest.to(MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90.0, 90.0))
                .speed(rotSpeedMin.get(), rotSpeedMax.get())
                .jitter(jitter.get())
                .silent(false) // flight physics follow the real view: client and server agree
                .priority(PRIORITY)
                .validWhile(this::isEnabled)
                .expiresAfter(3)
                .build());
    }

    /** Smoothed per-tick velocity of the target (teleports ignored). */
    private void trackTarget(LivingEntity target) {
        if (target == null) {
            trackedId = -1;
            lastTargetPos = null;
            targetVel = Vec3d.ZERO;
            return;
        }
        Vec3d p = target.getEntityPos();
        if (target.getId() == trackedId && lastTargetPos != null) {
            Vec3d dlt = p.subtract(lastTargetPos);
            targetVel = dlt.length() > 1.5 ? Vec3d.ZERO : targetVel.multiply(0.5).add(dlt.multiply(0.5));
        } else {
            targetVel = Vec3d.ZERO;
        }
        trackedId = target.getId();
        lastTargetPos = p;
    }

    @Override
    public String hudLabel() {
        return name + " · " + state.name().toLowerCase() + " · " + strikes + " hits";
    }
}
