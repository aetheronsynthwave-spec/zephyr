package dev.zephyr.modules.combat;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.DamageCalculator;
import dev.zephyr.core.event.ZephyrEvents;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;

/**
 * AutoMace - mace slam execution with two timing models:
 *
 * <p><b>STANDARD</b>: fall/velocity/height gate. Attacks when the local player is
 * descending ({@code velocity.y < 0}) with at least {@code minFall} blocks of accumulated
 * fall distance, sufficient height advantage over the target, and a closable gap to the
 * surface so the strike actually lands on the target rather than the floor.
 *
 * <p><b>DENSITY (0-tick)</b>: arms a same-tick trigger that fires the instant the landing
 * event (fired from the movement mixin with the last airborne fall distance) satisfies
 * the gate - the attack goes out in the same tick the server registers the smash landing,
 * which is exactly the timing a Density-mace player performs by hand.
 *
 * <p><b>TRIGGER</b> (default): strikes like a TriggerBot for falls. The tick the local
 * player is falling with a qualifying fall and a player is inside reach, it attacks,
 * swapping to the mace for that hit. No rotations are requested: it fires at whatever is
 * under the crosshair, or at the nearest visible player in reach when the crosshair is off
 * them. The server only needs the target in reach, so no aim help is needed.
 *
 * <p>All modes respect target hurt-time (server invulnerability frames), shields, and the
 * BreachSwap hotbar transition. STANDARD and DENSITY also use rotation convergence and
 * weapon cooldown.
 */
public final class AutoMace extends Module {

    private final ModeSetting mode = s(new ModeSetting("mode", "Mode",
            java.util.List.of("TRIGGER", "STANDARD", "DENSITY"), "TRIGGER"));

    private final NumberSetting minFall = num("minFall", "Min fall (blocks)", 0.5, 8.0, 0.25, 1.5);
    private final NumberSetting maxTargetDist = num("maxDist", "Max target dist", 1.5, 4.0, 0.1, 3.2);
    private final NumberSetting maxHurtTime = num("hurtTime", "Max target hurt time", 0, 10, 1, 5);
    /** TRIGGER: only players are struck (off has no effect on the nearest-player scan). */
    private final BooleanSetting playersOnly = bool("playersOnly", "Players only", true);
    /** TRIGGER: strike the nearest visible player in reach when the crosshair is off them. */
    private final BooleanSetting aimless = bool("aimless", "Strike without crosshair", true);
    /** TRIGGER: consecutive qualifying ticks required before the strike (0 = same tick). */
    private final NumberSetting triggerDelay = num("triggerDelay", "Trigger delay ticks", 0, 5, 1, 0);
    private final NumberSetting minCooldown = num("cooldown", "Min cooldown progress", 0.5, 1.0, 0.05, 0.9);
    private final NumberSetting rotSpeedMin = num("rotMin", "Rotation min deg/t", 5, 120, 5, 15);
    private final NumberSetting rotSpeedMax = num("rotMax", "Rotation max deg/t", 5, 180, 5, 30);
    private final NumberSetting jitter = num("jitter", "Rotation jitter", 0.0, 2.0, 0.05, 0.4);
    /** Off = no aim help at all: it only strikes when YOUR crosshair is already on the target. */
    private final BooleanSetting aim = bool("aim", "Aim at target", true);
    private final NumberSetting aimFov = num("aimFov", "Aim-off hit cone deg", 2, 45, 1, 12);
    private final BooleanSetting showSwap = bool("showSwap", "Show mace swap", true);
    private final NumberSetting backTicks = num("backTicks", "Swap-back ticks", 1, 10, 1, 3);
    private final BooleanSetting weaponOnly = bool("weaponOnly", "Only holding sword/axe", false);

    /** Stage shown in the HUD while a Density 0-tick strike is armed. */
    private boolean armedZeroTick;
    private int ticksSinceStrike = Integer.MAX_VALUE;
    /** A slam is building this tick (read by CombatCoordinator; AutoMace ticks first). */
    private boolean slamPending;
    private int tickCounter;
    /** TRIGGER: consecutive ticks the strike conditions have held (compared to triggerDelay). */
    private int triggerStreak;

    public AutoMace() {
        super("AutoMace", ModuleCategory.COMBAT, 0);
        ZephyrEvents.GROUND_LAND.subscribe(this::onLand); // once, not per toggle
    }

    /** AutoMace, ElytraMaceBot and SmartMaceBot all time the same smash: only one may run. */
    @Override
    protected String exclusiveGroup() {
        return "mace";
    }

    /** True while a qualifying drop toward a target is in progress (other hitters hold off). */
    public boolean slamPending() {
        return slamPending;
    }

    @Override
    protected void onDisable() {
        armedZeroTick = false;
        slamPending = false;
        triggerStreak = 0;
        RotationManager.clear(0);
    }

    private void onLand(ZephyrEvents.GroundLand event) {
        // Density 0-tick: strike in the same tick as the landing.
        if (!isEnabled() || !mode.is("DENSITY")) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || event.fallDistance < minFall.get()) {
            return;
        }
        LivingEntity target = TargetManager.currentTarget();
        if (target == null || !gateOk(client, self, target, event.fallDistance)) {
            return;
        }
        strike(client, self, target, event.fallDistance);
    }

    @Override
    public void onTick(MinecraftClient client) {
        tickCounter++;
        if (ticksSinceStrike < Integer.MAX_VALUE) {
            ticksSinceStrike++;
        }
        ClientPlayerEntity self = client.player;
        if (self == null) {
            return;
        }
        if (mode.is("TRIGGER")) {
            // TRIGGER picks its own target (crosshair or nearest in reach), so skip the
            // camera-tracking acquisition below.
            triggerTick(client, self);
            return;
        }
        // Acquire early (8 blocks) so the camera can track smoothly during the whole drop;
        // the strike itself is still gated on maxTargetDist.
        TargetCriteria criteria = new TargetCriteria().range(Math.max(8.0, maxTargetDist.get() + 1.5));
        LivingEntity target = TargetManager.acquire(client, criteria);
        TargetManager.cleanup();
        if (target == null) {
            armedZeroTick = false;
            slamPending = false;
            return;
        }

        double fall = self.fallDistance;
        double distToGround = DamageCalculator.distanceToGround(client.world, self);
        slamPending = self.getVelocity().y < -0.08
                && fall >= minFall.get() * 0.5
                && self.getY() - target.getY() >= 0.75
                && self.squaredDistanceTo(target) <= 36.0
                && dev.zephyr.core.inventory.InventoryManager.findHotbarSlot(self, ItemUtil::isMace) != -1;

        if (mode.is("DENSITY")) {
            // Arm while a qualifying slam is incoming: integrate gravity+drag forward
            // and arm only when the landing tick is 1-2 ticks away - tighter than the
            // crude "closable gap" heuristic, because the strike fires the tick the
            // server registers the smash landing.
            int ticksLeft = ticksToLanding(self, distToGround);
            armedZeroTick = self.getVelocity().y < -0.25
                    && fall >= minFall.get() * 0.75
                    && ticksLeft >= 0 && ticksLeft <= 2;
            aimAt(client, self, target);
            return; // the strike itself happens in onLand
        }

        // STANDARD gate: descending, fall banked, height advantage, ground gap closable.
        boolean descending = self.getVelocity().y < -0.08;
        boolean heightOk = self.getY() - target.getY() >= 0.75;
        boolean groundGapOk = distToGround <= 2.5;
        if (descending && heightOk && self.squaredDistanceTo(target) <= 36.0) {
            // Track the target smoothly through the whole fall, so the strike-window gate
            // finds the aim already settled instead of needing a last-moment flick.
            aimAt(client, self, target);
        }
        if (!descending || fall < minFall.get() || !heightOk || !groundGapOk) {
            return;
        }
        if (!gateOk(client, self, target, fall)) {
            return;
        }
        strike(client, self, target, fall);
    }

    /**
     * TRIGGER: strikes the tick a qualifying fall and a target in reach first coincide.
     * Uses no rotation requests, mirroring TriggerBot: the hit goes to whatever is in reach
     * and visible. Cooldown is not gated because the smash bonus ignores it at fall >= 1.5.
     */
    private void triggerTick(MinecraftClient client, ClientPlayerEntity self) {
        double fall = self.fallDistance;
        boolean falling = self.getVelocity().y < 0.0 && !self.isOnGround() && !self.isGliding()
                && !self.isTouchingWater() && !self.isInLava();
        // Other hitters yield while a slam is building toward a player within 6 blocks.
        slamPending = falling && fall >= minFall.get() * 0.5 && anyPlayerWithin(client, self, 6.0);
        if (!falling || fall < minFall.get()
                || dev.zephyr.core.inventory.InventoryManager.findHotbarSlot(self, ItemUtil::isMace) == -1) {
            triggerStreak = 0;
            return;
        }
        // Reach is capped at 3 blocks so the trigger never reaches past vanilla melee range.
        double reach = Math.min(3.0, maxTargetDist.get());
        LivingEntity target = triggerTarget(client, self, reach);
        if (target == null || target.hurtTime > maxHurtTime.get().intValue()
                || dev.zephyr.core.combat.CombatCoordinator.shieldBlocks(target, self)) {
            triggerStreak = 0;
            return;
        }
        triggerStreak++;
        if (triggerStreak <= triggerDelay.get().intValue()) {
            return;
        }
        if (strike(client, self, target, fall)) {
            triggerStreak = 0;
        }
    }

    /**
     * TRIGGER target choice. The crosshair player wins when it is a valid enemy in reach.
     * Otherwise, with aimless on, the nearest visible player in reach is used. Only the
     * world's player list is scanned, never every entity.
     */
    private LivingEntity triggerTarget(MinecraftClient client, ClientPlayerEntity self, double reach) {
        Vec3d eye = self.getEyePos();
        double reachSq = reach * reach;
        if (client.crosshairTarget instanceof EntityHitResult ehr
                && ehr.getEntity() instanceof LivingEntity living
                && living != self && living.isAlive() && !self.isTeammate(living)
                && (!playersOnly.get() || living instanceof PlayerEntity)
                && living.getBoundingBox().squaredMagnitude(eye) <= reachSq) {
            return living;
        }
        if (!aimless.get()) {
            return null;
        }
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == self || !p.isAlive() || p.isSpectator() || self.isTeammate(p)) {
                continue;
            }
            double distSq = p.getBoundingBox().squaredMagnitude(eye);
            if (distSq > reachSq || distSq >= bestSq
                    || !RaycastValidator.hitboxVisible(eye, p.getBoundingBox(), self)) {
                continue;
            }
            best = p;
            bestSq = distSq;
        }
        return best;
    }

    /** True when a living, non-teammate player stands within {@code radius} blocks. */
    private static boolean anyPlayerWithin(MinecraftClient client, ClientPlayerEntity self, double radius) {
        double radiusSq = radius * radius;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p != self && p.isAlive() && !p.isSpectator() && !self.isTeammate(p)
                    && self.squaredDistanceTo(p) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    /** Shared pre-strike gates: hurt-time, cooldown, reach, rotation aim. */
    private boolean gateOk(MinecraftClient client, ClientPlayerEntity self, LivingEntity target, double fall) {
        if (target.hurtTime > maxHurtTime.get().intValue()) {
            return false;
        }
        // A smash into a raised shield is fully blocked. With ShieldBreaker on, its axe hit
        // opens the guard this tick and the slam lands next tick (stun slam).
        if (dev.zephyr.core.combat.CombatCoordinator.shieldBlocks(target, self)) {
            return false;
        }
        double distSq = self.squaredDistanceTo(target);
        double reach = maxTargetDist.get();
        if (distSq > reach * reach) {
            return false;
        }
        // The smash bonus is added after the cooldown scaling, so a real slam (fall above
        // the threshold) lands its damage at any cooldown - and swapping to the mace mid-air
        // resets the cooldown, which made this gate block every strike of a normal drop.
        if (fall < Math.max(1.5, minFall.get()) && ItemUtil.attackCooldownProgress(self) < minCooldown.get()) {
            return false;
        }
        Vec3d aim = MathUtil.bestAimPoint(self, target,
                RotationManager.currentYaw(), self.getPitch());
        if (!this.aim.get()) {
            // No aimbot: strike only when the player's own view is on the target.
            return RotationManager.isAimedAt(aim, aimFov.get());
        }
        RotationManager.request(RotationRequest.to(
                        MathUtil.yawTo(self.getEyePos(), aim), MathUtil.pitchTo(self.getEyePos(), aim))
                .speed(rotSpeedMin.get(), rotSpeedMax.get())
                .jitter(jitter.get())
                .silent(false)
                .validWhile(() -> target.isAlive())
                .expiresAfter(4)
                .build());
        return RotationManager.isAimedAt(aim, 6.0);
    }

    /**
     * Integrated landing forecast: steps the vertical velocity through vanilla
     * gravity (0.08/tick) and drag (0.98/tick) and returns the tick index at which
     * the player reaches the surface, or 99 when that is more than 10 ticks out.
     */
    private static int ticksToLanding(ClientPlayerEntity self, double distToGround) {
        if (self.isOnGround()) {
            return 0;
        }
        double vy = self.getVelocity().y;
        double fallen = 0.0;
        for (int t = 1; t <= 10; t++) {
            vy = (vy - 0.08) * 0.98;
            fallen += -vy;
            if (fallen >= distToGround) {
                return t;
            }
        }
        return 99;
    }

    /** Requests the BreachSwap transition, then attacks through the vanilla path. */
    private boolean strike(MinecraftClient client, ClientPlayerEntity self, LivingEntity target, double fall) {
        // Attribute swap: the hit goes out with the mace's attributes (smash damage) while
        // you keep holding your sword. With BreachSwap on, a Breach mace is preferred.
        BreachSwap breach = ZephyrClient.modules().get(BreachSwap.class);
        int slot = -1;
        if (breach != null && breach.isEnabled()) {
            slot = dev.zephyr.core.inventory.InventoryManager.findHotbarSlot(self,
                    s -> ItemUtil.isMace(s) && ItemUtil.enchantLevel(s, "breach") > 0);
        }
        if (slot == -1) {
            slot = dev.zephyr.core.inventory.InventoryManager.findHotbarSlot(self, ItemUtil::isMace);
        }
        if (slot == -1) {
            return false; // no mace on the hotbar
        }
        if (weaponOnly.get()) {
            String held = ItemUtil.id(self.getMainHandStack());
            if (!(held.endsWith("_sword") || held.endsWith("_axe"))) {
                return false;
            }
        }
        int back = showSwap.get() ? backTicks.get().intValue() : 0;
        if (CombatActions.attributeSwapAttack(client, slot, target, back)) {
            ticksSinceStrike = 0;
            armedZeroTick = false;
            return true;
        }
        return false;
    }

    private void aimAt(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        if (!this.aim.get()) {
            return;
        }
        Vec3d aim = MathUtil.aimPoint(target);
        RotationManager.request(RotationRequest.to(
                        MathUtil.yawTo(self.getEyePos(), aim), MathUtil.pitchTo(self.getEyePos(), aim))
                .speed(rotSpeedMin.get(), rotSpeedMax.get())
                .jitter(jitter.get())
                .silent(false)
                .validWhile(() -> target.isAlive())
                .expiresAfter(4)
                .build());
    }

    @Override
    public String hudLabel() {
        StringBuilder label = new StringBuilder(name).append(" · ").append(mode.get());
        if (armedZeroTick) {
            label.append(" · armed");
        }
        return label.toString();
    }
}
