package dev.zephyr.modules.crystal;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.DamageCalculator;
import dev.zephyr.core.crystal.CrystalSolver;
import dev.zephyr.core.crystal.CrystalSolver.Spot;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.timing.RandomController;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.core.world.EntityScan;
import dev.zephyr.core.world.HitGeometry;
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * AutoHitCrystal - the full engagement loop, on autopilot:
 *
 * <pre>  MELEE hit with ANY item (cooldown-aware, so swords/delayed weapons are respected)
 *     |   (the instant the hit lands - or without a hit, see "Crystal without a hit")
 *     v
 *  SOLVE: math-scan every cell around the target for the best crystal blast; when the
 *         winning cell has no obsidian/bedrock support, solve where to PLACE obsidian
 *         right now so that cell.up() becomes the highest-value crystal spot
 *     v
 *  OBSIDIAN: swap to obsidian, place it at the solved cell (ms-paced micro FSM)
 *     v
 *  SPAM: swap to crystals, place on the solved support and detonate at randomized
 *        15-25 CPS - continuously, until the target is DOWN or OUT OF RANGE
 *     |   (no trigger needed - the loop folds back automatically)
 *     v
 *  MELEE again ... repeat until the fight is over.</pre>
 *
 * <p>The melee hit is the comb opener: it carries the weapon damage and the knockback
 * that pins the target next to the blast cell, which is exactly when the crystal math
 * has its highest expected value. Everything the server judges - reach, cooldown,
 * entity-blocked placements, blast self-damage - is validated before a click goes out,
 * and every swap is a vanilla hotbar/inventory click.
 */
public final class AutoHitCrystal extends Module {

    private final NumberSetting meleeRange = num("meleeRange", "Melee reach", 2.0, 4.0, 0.05, 3.0);
    private final NumberSetting minCooldown = num("cooldown", "Min attack cooldown", 0.5, 1.0, 0.05, 0.95);
    /** Every sword hit is a knockback hit: Knockback sword, full charge, sprint hit when moving. */
    private final BooleanSetting knockbackHits = bool("kbHits", "Knockback hits", true);
    private final NumberSetting maxHurtTime = num("hurtTime", "Max target hurt time", 0, 10, 1, 0);
    private final NumberSetting placeRange = num("placeRange", "Solve range", 2.0, 6.0, 0.1, 4.5);
    private final NumberSetting attackRange = num("attackRange", "Crystal attack range", 2.0, 6.0, 0.1, 4.0);
    private final NumberSetting reEngageRange = num("reRange", "Re-engage range", 2.0, 8.0, 0.1, 5.0);
    private final NumberSetting minTargetDamage = num("minDmg", "Min target dmg", 0.0, 20.0, 0.5, 3.0);
    private final NumberSetting maxSelfDamage = num("maxSelf", "Max self dmg", 0.0, 20.0, 0.5, 13.0);
    private final NumberSetting keepHealth = num("keepHp", "Keep at least hp", 1.0, 12.0, 0.5, 4.0);
    private final NumberSetting facePlaceHealth = num("faceplace", "Faceplace below hp", 0.0, 12.0, 0.5, 3.0);
    private final NumberSetting cpsMin = num("cpsMin", "CPS min", 4, 30, 0.5, 20);
    private final NumberSetting cpsMax = num("cpsMax", "CPS max", 4, 30, 0.5, 26);
    private final NumberSetting swapMin = num("swapMin", "Swap delay min ms", 5, 100, 1, 5);
    private final NumberSetting swapMax = num("swapMax", "Swap delay max ms", 5, 150, 1, 20);
    private final NumberSetting rotMin = num("rotMin", "Rotation min deg/t", 5, 120, 5, 45);
    private final NumberSetting rotMax = num("rotMax", "Rotation max deg/t", 5, 180, 5, 90);

    private final BooleanSetting useObsidian = bool("obsidian", "Place obsidian supports", true);
    /** Crystals detonated per sword hit before going back to the sword */
    private final NumberSetting perHit = num("perHit", "Crystals per hit", 1, 30, 1, 4);
    /** Turn straight to each click instead of easing there over several ticks. */
    private final BooleanSetting instantAim = bool("instantAim", "Instant aim", true);
    /** Break a crystal and place the next one in the same tick. */
    private final BooleanSetting sameTick = bool("sameTick", "Break + place same tick", true);
    private final NumberSetting placeDelay = num("placeDelay", "Place delay ticks", 0, 10, 1, 0);
    private final NumberSetting breakDelay = num("breakDelay", "Break delay ticks", 0, 10, 1, 0);
    private final NumberSetting swordDelay = num("swordDelay", "Sword return delay ms", 0, 500, 10, 0);
    /** With the target in sword reach, wait for the charged hit rather than crystal without one. */
    private final BooleanSetting swordFirst = bool("swordFirst", "Sword first in reach", true);
    /** Start crystalling whenever the sword cannot hit right now, not only right after a hit. */
    private final BooleanSetting freeOpen = bool("freeOpen", "Crystal without a hit", true);
    /** Target this many blocks above (or below) us counts as us having (or losing) the edge. */
    private final NumberSetting heightEdge = num("heightEdge", "Height edge blocks", 0.1, 2.0, 0.1, 0.4);
    /**
     * A clean sword hit (theirs did not land on us too) owes an obsidian block and its
     * crystals before the next swing. After a trade the module just carries on.
     */
    private final BooleanSetting hitSequence = bool("sequence", "Obsidian after clean hits", true);
    /** Longest wait for the knocked-back target to be buildable again before giving up. */
    private static final int OWED_TICKS = 30;
    private boolean owed;
    private boolean firstCrystal;
    private int owedTicks;
    private int builtHits;
    private int cleanHits;
    private int cycles;
    private final NumberSetting selfWeight = num("selfWeight", "Self damage weight", 0.0, 3.0, 0.1, 0.8);
    /** Being above the target is the losing side: costlier blasts, tighter self limit. */
    private final NumberSetting heightPenalty = num("heightPenalty", "Penalty for being higher", 0.0, 3.0, 0.1, 1.0);
    private final BooleanSetting stayOffObsidian = bool("offObsidian", "Don't climb onto obsidian", true);
    private final NumberSetting climbFor = num("climbFor", "Climb if target higher by", 0.5, 5.0, 0.1, 1.5);
    private int sinceHit;
    private int spamTicks;
    private long swordAfterMs;
    private long nextOpenMs;
    private final NumberSetting aimTolerance = num("aimTol", "Aim tolerance deg", 3, 30, 0.5, 14);
    private final BooleanSetting predictTarget = bool("predict", "Predict target motion", true);
    private final BooleanSetting requireWallsRange = bool("walls", "Respect walls", false);
    private final BooleanSetting pauseOnLoot = bool("pauseLoot", "Pause near loot", false);

    private enum Stage { MELEE, OBSIDIAN, SPAM }

    /** Out-of-crystals / unsolvable backoff before folding back to the sword. */
    private static final int NO_CRYSTAL_GIVE_UP_TICKS = 5;

    /** Nothing reachable to crystal: hand control back to the sword this quickly. */
    private static final int NO_SPOT_GIVE_UP_TICKS = 6;
    private static final double REACH_MARGIN = 0.15;

    private Stage stage = Stage.MELEE;
    private final MsTimer stageTimer = new MsTimer();
    private final MsTimer attackTimer = new MsTimer();
    private LivingEntity target;
    private Spot plan;
    private int meleeHotbarSlot = -1;
    private int failTicks;
    private double lastCps;
    private int detonatedCount;
    private int obsidianPlacedCount;
    private int tick;
    private int cycleStart = -1;
    private int firstBlastTicks;
    private int firstBlasts;
    private int lastPlaceTick = -100;
    private int lastBreakTick = -100;
    /** Ticks left in which to open at once: we have just pearled in under the target. */
    private int chaseTicks;
    /** Crystals already attacked (entity id to tick): the client still shows them for a moment. */
    private final java.util.Map<Integer, Integer> broken = new java.util.HashMap<>();
    private static final int BROKEN_TICKS = 6;

    @Override
    protected String exclusiveGroup() {
        return "crystal";
    }

    public AutoHitCrystal() {
        super("AutoHitCrystal", ModuleCategory.CRYSTAL, 0);
    }

    @Override
    protected void onEnable() {
        stage = Stage.MELEE;
        attackTimer.restartCps(cpsMin.get(), 15);
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (self != null) {
            meleeHotbarSlot = InventoryManager.selectedHotbar(self);
        }
    }

    @Override
    protected void onDisable() {
        stage = Stage.MELEE;
        plan = null;
        target = null;
        broken.clear();
        owed = false;
        firstCrystal = false;
        failTicks = 0;
        RotationManager.clear(0);
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (self == null || world == null) {
            stage = Stage.MELEE;
            plan = null;
            return;
        }

        double searchRange = Math.max(reEngageRange.get(), attackRange.get()) + 1.0;
        TargetCriteria criteria = new TargetCriteria().range(searchRange);
        target = TargetManager.acquire(client, criteria);
        TargetManager.cleanup();
        if (target == null || !target.isAlive()) {
            // Fight over (or no one around): fold back to melee phase and wait.
            stage = Stage.MELEE;
            plan = null;
            owed = false;
            firstCrystal = false;
            return;
        }
        if (pauseOnLoot.get() && lootNearby(world, target)) {
            return;
        }

        CrystalSolver.selfWeight = selfWeight.get();
        CrystalSolver.heightPenalty = heightPenalty.get();

        AnchorMacro anchor = stockedAnchor(self);
        if (anchor != null && dev.zephyr.core.combat.ExplosiveTurn.anchorHasTurn()) {
            // The anchor chain has the hotbar and the aim until it goes off or stalls.
            stage = Stage.MELEE;
            plan = null;
            owed = false;
            return;
        }
        tick++;
        broken.values().removeIf(at -> tick - at > BROKEN_TICKS);
        if (chaseTicks > 0) {
            chaseTicks--;
        }
        Stage before = stage;
        double dist = self.distanceTo(target);

        if (stage == Stage.MELEE && chaseTicks == 0) {
            firstCrystal = false;
        }
        switch (stage) {
            case MELEE -> meleePhase(client, self, world, target, dist);
            case OBSIDIAN -> obsidianPhase(client, self, world, target, dist);
            case SPAM -> spamPhase(client, self, world, target, dist);
        }
        if (stage != Stage.MELEE || owed) {
            dev.zephyr.core.combat.ExplosiveTurn.crystalActive();
        } else if (before != Stage.MELEE && anchor != null) {
            dev.zephyr.core.combat.ExplosiveTurn.offerAnchor();
        }
    }

    // ------------------------------------------------------------------
    // Phase 1: melee opener with any item
    // ------------------------------------------------------------------

    private void meleePhase(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                            LivingEntity target, double dist) {
        // Just landed under them: obsidian and crystals before anything else.
        if (chaseTicks > 0 && open(client, self, world, target)) {
            chaseTicks = 0;
            firstCrystal = true; // the block is built for this one blast: it gets its crystal
            return;
        }
        // THE SWORD COMES FIRST: every melee hit is thrown with the recorded weapon
        // slot. If the previous crystal cycle left crystals/obsidian in hand, the
        // module visibly swaps back to the sword BEFORE the swing - and the next
        // crystal phase never destroys that slot (see ensureItemSelected).
        if (System.currentTimeMillis() < swordAfterMs) {
            return; // brief pause before swapping back, like a player would
        }
        if (knockbackHits.get() && dev.zephyr.core.combat.SwapManager.isValidSlot(meleeHotbarSlot)
                && !hasKnockback(self.getInventory().getStack(meleeHotbarSlot))) {
            int knockbackSlot = InventoryManager.findHotbarSlot(self, AutoHitCrystal::hasKnockback);
            if (knockbackSlot != -1) {
                meleeHotbarSlot = knockbackSlot;
            }
        }
        if (meleeHotbarSlot != -1
                && InventoryManager.selectedHotbar(self) != meleeHotbarSlot) {
            if (!dev.zephyr.core.combat.SwapManager.isValidSlot(meleeHotbarSlot)) {
                meleeHotbarSlot = InventoryManager.selectedHotbar(self); // corrupt index: re-anchor
            } else {
                // A refusal only means "not this tick" (slot budget used): keep the sword
                // slot and retry on the next pre-movement stage.
                InventoryManager.selectHotbar(client, meleeHotbarSlot);
            }
            return; // the hit goes out next tick, after the swap has landed server-side
        }

        // No hit needed: crystal whenever the sword cannot connect this tick anyway, and at
        // once when the target is above us (they eat the whole blast, our block shields us).
        // Never while WE are the higher one: that is the side that gets hurt.
        double needed = knockbackHits.get() ? Math.max(minCooldown.get(), 0.95) : minCooldown.get();
        boolean swordReady = dist <= meleeRange.get() && target.hurtTime <= maxHurtTime.get().intValue()
                && ItemUtil.attackCooldownProgress(self) >= needed;
        double edge = heightOver(self, target);
        boolean oneSided = edge >= heightEdge.get() || launched(world, target);
        boolean waitForSword = swordFirst.get() && dist <= meleeRange.get();
        if (freeOpen.get() && edge > -heightEdge.get() && (oneSided || !swordReady && !waitForSword)
                && System.currentTimeMillis() >= nextOpenMs) {
            nextOpenMs = System.currentTimeMillis() + 100;
            if (open(client, self, world, target)) {
                return;
            }
        }

        if (dist > meleeRange.get()) {
            return; // not in sword range yet
        }
        if (target.hurtTime > maxHurtTime.get().intValue()) {
            return; // server invulnerability frames
        }
        if (dev.zephyr.core.combat.CombatCoordinator.meleeShouldYield(client, target)) {
            return; // let AutoMace / ShieldBreaker take this hit
        }
        // Weapon-delay aware: swords/axes carry a full 1.0-scaled cooldown; respecting
        // it keeps every hit at full strength and the rhythm legal.
        // Vanilla only gives the sprint knockback above 90 % charge.
        if (ItemUtil.attackCooldownProgress(self) < needed) {
            return;
        }

        Vec3d aim = MathUtil.aimPoint(target);
        requestRotation(self, aim);
        if (!RotationManager.isAimedAt(aim, aimTolerance.get())) {
            return;
        }

        // A hit ends the sprint, so start it again before the next one (a W-tap). Only while
        // already walking forward; the server needs this tick to learn about it.
        if (knockbackHits.get() && !self.isSprinting() && canSprint(self)) {
            self.setSprinting(true);
            return;
        }

        boolean sprinting = self.isSprinting();
        boolean knockbackSword = hasKnockback(self.getMainHandStack());
        if (CombatActions.attackEntity(client, target)) {
            meleeHits++;
            if (sprinting) {
                sprintHits++;
            }
            if (knockbackSword) {
                enchantHits++;
            }
            // THE TRIGGER MOMENT: solve the obsidian/crystal placement right now.
            meleeHotbarSlot = InventoryManager.selectedHotbar(self);
            if (hitSequence.get() && self.hurtTime == 0 && useObsidian.get() && hasItem(self, ItemUtil::isObsidian)
                    && hasItem(self, ItemUtil::isEndCrystal)) {
                owed = true;
                cycleStart = tick;
                cleanHits++;
                cycles++;
                owedTicks = 0;
                sinceHit = 0;
                spamTicks = 0;
                failTicks = 0;
                plan = null;
                stage = Stage.OBSIDIAN;
                stageTimer.restart(0.0, 4.0);
            } else {
                open(client, self, world, target);
            }
        }
    }

    /** Drop whatever is under way and open on the target the moment there is a spot. */
    public void strikeNow() {
        chaseTicks = 30;
        stage = Stage.MELEE;
        plan = null;
        owed = false;
        nextOpenMs = 0;
        swordAfterMs = 0;
    }

    /** Solves a crystal spot and moves to the stage that builds on it; false if none is worth it. */
    private boolean open(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                         LivingEntity target) {
        sinceHit = 0;
        spamTicks = 0;
        cycles++;
        if (!hasItem(self, ItemUtil::isEndCrystal)) {
            return false; // nothing to follow up with: keep swinging
        }
        Spot solved = solve(client, self, world, target);
        if (solved == null || solved.needsObsidian() && !(useObsidian.get() && hasItem(self, ItemUtil::isObsidian))) {
            return false; // nothing to gain from crystals; keep swinging
        }
        plan = solved;
        failTicks = 0;
        cycleStart = tick;
        if (solved.needsObsidian()) {
            stage = Stage.OBSIDIAN;
            stageTimer.restart(2.0, 9.0); // "very moment the hit is triggered"
        } else {
            stage = Stage.SPAM;
            stageTimer.restart(0.0, 8.0);
        }
        return true;
    }

    /** How far the target's feet are above ours; positive is the side we want to be on. */
    private static double heightOver(ClientPlayerEntity self, LivingEntity target) {
        return target.getY() - self.getY();
    }

    // ------------------------------------------------------------------
    // Phase 2: obsidian support placement (when solved spot has no support)
    // ------------------------------------------------------------------

    private void obsidianPhase(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                               LivingEntity target, double dist) {
        if (owed && self.hurtTime > 0) {
            // They hit back after all: a trade, not a combo. Carry on as usual.
            owed = false;
            cleanHits--;
            plan = null;
            if (!open(client, self, world, target)) {
                stage = Stage.MELEE;
                returnToSword(client);
            }
            return;
        }
        if (owed) {
            // Re-solved every tick: the target is still flying from the knockback.
            CrystalSolver.Rated owedSpot = CrystalSolver.findOptimalSpot(world, self, target,
                    placeRange.get(), 0.0, 20.0, true, true, cell -> breakable(self, cell));
            plan = owedSpot == null ? null : owedSpot.spot();
            if (plan != null && !plan.needsObsidian()) {
                owed = false; // their ledge already has obsidian: straight to the crystals
                firstCrystal = true;
            }
            if (++owedTicks > OWED_TICKS || !hasItem(self, ItemUtil::isObsidian)
                    || dist > reEngageRange.get() + 1.0) {
                owed = false;
                plan = null;
                stage = Stage.MELEE;
                returnToSword(client);
                return;
            }
            if (plan == null) {
                // Nowhere to build yet: hold the obsidian and keep looking at the target.
                ensureObsidianSelected(client, self);
                aimAt(self, MathUtil.aimPoint(target));
                return;
            }
            failTicks = 0;
        }
        if (plan == null || !plan.needsObsidian()
                || world.getBlockState(plan.obsidianCell).isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
            stage = Stage.SPAM;
            stageTimer.restart(0.0, 8.0);
            return;
        }
        if (!useObsidian.get() || !hasItem(self, ItemUtil::isObsidian)) {
            stage = Stage.MELEE;
            return;
        }

        aimAt(self, Vec3d.ofCenter(plan.obsidianCell));
        if (!stageTimer.isDue()
                || !RotationManager.isAimedAt(Vec3d.ofCenter(plan.obsidianCell), aimTolerance.get())) {
            return; // place only once the camera has actually arrived (no mid-flick clicks)
        }
        stageTimer.restart(swapMin.get(), swapMax.get());

        if (!ensureObsidianSelected(client, self)) {
            if (++failTicks > 20) {
                stage = Stage.MELEE;
            }
            return;
        }
        BlockHitResult hit = plan.obsidianHit != null
                ? plan.obsidianHit
                : CrystalSolver.blockPlacementHit(world, plan.obsidianCell, self.getEyePos());
        if (hit == null) {
            stage = Stage.MELEE;
            return;
        }
        if (CombatActions.interactBlock(client, hit).isAccepted()
                && world.getBlockState(plan.obsidianCell).isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
            obsidianPlacedCount++;
            if (owed) {
                owed = false;
                firstCrystal = true;
                builtHits++;
            }
            stage = Stage.SPAM;
            stageTimer.restart(0.0, 8.0);
        } else if (++failTicks > 8) {
            stage = Stage.MELEE;
        }
    }

    // ------------------------------------------------------------------
    // Phase 3: crystal spam until the target is down or out of range
    // ------------------------------------------------------------------

    private void spamPhase(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                           LivingEntity target, double dist) {
        // Stuck guard: nothing detonated for ~1.5 s -> back to the sword instead of sitting on crystals.
        if (++spamTicks > 30) {
            spamTicks = 0;
            stage = Stage.MELEE;
            plan = null;
            swordAfterMs = System.currentTimeMillis();
            return;
        }
        // After the per-hit crystals go back to the sword, ready for the next hit.
        if (sinceHit >= wantedCrystals(self, world, target)) {
            stage = Stage.MELEE;
            plan = null;
            swordAfterMs = System.currentTimeMillis() + swordDelay.get().intValue();
            return;
        }
        // Loop exit: target DOWN or OUT OF RANGE -> swap back to the sword first,
        // then fold back to the melee phase automatically.
        if (!target.isAlive() || dist > reEngageRange.get()) {
            stage = Stage.MELEE;
            plan = null;
            returnToSword(client);
            return;
        }

        // Break the crystal on this cycle's block, at the randomized CPS rhythm.
        if (tryAttackCycleCrystal(client, self, world, target)) {
            return;
        }

        // No live crystal in range: (re)place on the plan support.
        if (!stageTimer.isDue()) {
            return;
        }
        if (!hasItem(self, ItemUtil::isEndCrystal)) {
            // Out of crystals: stop burning ticks in this phase - swap back to the
            // sword and let the melee loop finish the fight (or re-trigger later).
            // A few ticks' grace so the last crystal can appear and be broken above.
            if (++failTicks > NO_CRYSTAL_GIVE_UP_TICKS) {
                stage = Stage.MELEE;
                failTicks = 0;
                returnToSword(client);
            }
            return;
        }

        // This cycle's own block. It is replaced (by another new one, never an old one)
        // only when it can no longer do the job, or when they are in the air and a new
        // block would hit clearly harder.
        CrystalSolver.Rated kept = plan != null && isCrystalBase(world, plan.support)
                ? CrystalSolver.rate(world, self, target, plan.support, placeRange.get(),
                        cycleMinDamage(target), selfBudget(self), cell -> breakable(self, cell))
                : null;
        Spot solved = kept == null ? null : kept.spot();
        if (kept == null || launched(world, target)) {
            CrystalSolver.Rated fresh = solveRated(self, world, target);
            if (fresh != null && (kept == null || fresh.value() > kept.value() + 2.0)) {
                solved = fresh.spot();
            }
        }
        if (solved == null) {
            failTicks++;
            if (failTicks > NO_SPOT_GIVE_UP_TICKS) {
                stage = Stage.MELEE; // no geometry worth crystals - back to sword
                failTicks = 0;
                returnToSword(client);
            }
            return;
        }
        plan = solved;
        failTicks = 0;

        if (!isCrystalBase(world, plan.support)) {
            // The support was never placed (or is gone): build it before any crystal click.
            if (plan.needsObsidian()) {
                stage = Stage.OBSIDIAN;
                failTicks = 0;
                stageTimer.restart(2.0, 9.0);
            } else {
                plan = null;
            }
            return;
        }
        if (crystalAt(world, plan.crystalCell)) {
            return; // ours is already there: wait until it can be broken
        }
        // A click is on its way (give the server time to answer), or a delay is set.
        if (tick - lastPlaceTick <= Math.max(1, placeDelay.get().intValue())
                || tick - lastBreakTick < placeDelay.get().intValue()) {
            return;
        }
        if (DamageCalculator.crystalDamage(world, Vec3d.ofBottomCenter(plan.crystalCell), self) > selfBudget(self)) {
            plan = null; // too dangerous at our current health
            if (++failTicks > NO_SPOT_GIVE_UP_TICKS) {
                stage = Stage.MELEE;
                failTicks = 0;
                returnToSword(client);
            }
            return;
        }
        if (!ensureCrystalSelected(client, self)) {
            return;
        }
        aimAt(self, supportAim(plan));
        if (!RotationManager.isAimedAt(supportAim(plan), aimTolerance.get())) {
            return;
        }
        placeCrystal(client, self, world, false);
    }

    private static boolean isCrystalBase(ClientWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.isOf(net.minecraft.block.Blocks.OBSIDIAN) || state.isOf(net.minecraft.block.Blocks.BEDROCK);
    }

    // ------------------------------------------------------------------
    // Shared actions
    // ------------------------------------------------------------------

    /**
     * Detonates the crystal standing on this cycle's block, and no other: leftovers from
     * earlier cycles and the target's own crystals never pull the aim away. True while
     * there is one being dealt with.
     */
    private boolean tryAttackCycleCrystal(MinecraftClient client, ClientPlayerEntity self,
                                          ClientWorld world, LivingEntity target) {
        if (plan == null) {
            return false;
        }
        var there = world.getEntitiesByClass(EndCrystalEntity.class, new Box(plan.crystalCell),
                e -> e.isAlive() && !broken.containsKey(e.getId()) && e.getBlockPos().equals(plan.crystalCell));
        if (there.isEmpty()) {
            return false;
        }
        EndCrystalEntity best = there.get(0);
        if (!HitGeometry.withinReach(self.getEyePos(), best.getBoundingBox(), attackReach(self))
                || !RaycastValidator.hitboxVisible(self.getEyePos(), best.getBoundingBox(), self)) {
            return false;
        }
        double targetDmg = DamageCalculator.crystalDamageAt(world, best.getLerpedPos(1.0F), target,
                CrystalSolver.predictFeet(world, target, predictTarget.get() ? 1 : 0), false);
        double selfDmg = DamageCalculator.crystalDamage(world, best.getLerpedPos(1.0F), self);
        if (targetDmg < cycleMinDamage(target)
                || selfDmg > self.getHealth() + self.getAbsorptionAmount() - keepHealth.get()) {
            return false;
        }
        if (!attackTimer.isDue() || tick - lastPlaceTick < breakDelay.get().intValue()) {
            return true;
        }
        // The same point the crystal was placed from: the top face of its block, which is also
        // the bottom of the crystal, so no turn is needed between placing and breaking.
        Vec3d aim = supportAim(plan);
        requestRotation(self, aim);
        if (!RotationManager.isAimedAt(aim, aimTolerance.get())) {
            return true; // rotation still converging; keep the timer armed
        }
        attackTimer.restartCps(lastCps = RandomController.uniform(cpsMin.get(), cpsMax.get()), 15);
        if (CombatActions.attackEntity(client, best)) {
            detonatedCount++;
            if (cycleStart >= 0) {
                firstBlastTicks += tick - cycleStart;
                firstBlasts++;
                cycleStart = -1;
            }
            sinceHit++;
            spamTicks = 0;
            firstCrystal = false;
            lastBreakTick = tick;
            broken.put(best.getId(), tick);
            lastPlaceTick = -100; // the cell is free again
            stageTimer.restart(0.0, 8.0);
            // The server handles the attack before the click, so the next crystal can go now.
            if (sameTick.get() && placeDelay.get() < 1 && sinceHit < wantedCrystals(self, world, target)
                    && ItemUtil.isEndCrystal(self.getMainHandStack())
                    && DamageCalculator.crystalDamage(world, Vec3d.ofBottomCenter(plan.crystalCell), self)
                    <= selfBudget(self)) {
                placeCrystal(client, self, world, true);
            }
        }
        return true;
    }

    private static Vec3d supportAim(Spot spot) {
        return Vec3d.ofBottomCenter(spot.support).add(0.0, 1.0, 0.0);
    }

    /**
     * Clicks a crystal onto the cycle's block. {@code replacing}: the crystal there was
     * attacked this tick, so the client still sees it and predicts a failure; the click is
     * sent all the same.
     */
    private void placeCrystal(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                              boolean replacing) {
        BlockHitResult hit = CrystalSolver.crystalPlacementHit(world, plan.support, self.getEyePos());
        if (hit == null) {
            return;
        }
        var result = CombatActions.interactBlock(client, hit);
        if (replacing && !result.isAccepted()) {
            CombatActions.swing(client);
        }
        if (replacing || result.isAccepted()) {
            lastPlaceTick = tick;
            // The attack timer may break it as soon as the crystal entity exists.
            attackTimer.restartCps(lastCps = RandomController.uniform(cpsMax.get(), cpsMax.get() + 4), 15);
        }
        stageTimer.restart(swapMin.get(), swapMax.get()); // also paces refused clicks
    }

    private int wantedCrystals(ClientPlayerEntity self, ClientWorld world, LivingEntity target) {
        int wanted = perHit.get().intValue();
        // SMART: finish low targets - keep crystalling while they are at 6 hp or less.
        if (smart() && target.getHealth() + target.getAbsorptionAmount() <= 6.0f) {
            wanted = Math.max(wanted, 3);
        }
        // While they are above us the crystals are one-sided: keep going instead of swinging.
        if (heightOver(self, target) >= heightEdge.get() || launched(world, target)) {
            wanted = Math.max(wanted, 12);
        }
        return wanted;
    }

    /**
     * Weakest blast still worth breaking on the block we are working. The block built after a
     * clean hit gets its crystal even if it hits lightly (heavy armour, flat ground).
     */
    private double cycleMinDamage(LivingEntity target) {
        return firstCrystal ? 0.5 : effectiveMinDamage(target);
    }

    /** Most self damage a blast may do right now: the setting, but never below the kept health. */
    private double selfBudget(ClientPlayerEntity self) {
        double spare = self.getHealth() + self.getAbsorptionAmount() - keepHealth.get();
        double limit = firstCrystal ? 20.0 : maxSelfDamage.get();
        double above = target == null ? 0.0 : self.getY() - target.getY();
        if (above > heightEdge.get()) {
            limit /= 1.0 + heightPenalty.get() * Math.min(2.0, above);
        }
        return Math.max(0.0, Math.min(limit, spare));
    }

    /** Crystal attack reach, eye to hitbox: the setting, never past the player's real reach. */
    private double attackReach(ClientPlayerEntity self) {
        return Math.min(attackRange.get(), self.getEntityInteractionRange());
    }

    /** True while a crystal already sits in this cell (placing there again can only fail). */
    private boolean crystalAt(ClientWorld world, BlockPos cell) {
        return !world.getEntitiesByClass(EndCrystalEntity.class, new Box(cell),
                e -> e.isAlive() && !broken.containsKey(e.getId())).isEmpty();
    }

    /**
     * The solve: the best place for a NEW block of ours, by calculation. Only a ledge that
     * already carries obsidian is used as it is; without obsidian, whatever is there.
     */
    private Spot solve(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                       LivingEntity target) {
        CrystalSolver.Rated best = solveRated(self, world, target);
        return best == null ? null : best.spot();
    }

    private CrystalSolver.Rated solveRated(ClientPlayerEntity self, ClientWorld world, LivingEntity target) {
        boolean canObsidian = useObsidian.get() && hasItem(self, ItemUtil::isObsidian);
        return CrystalSolver.findOptimalSpot(world, self, target, placeRange.get(),
                effectiveMinDamage(target), selfBudget(self), canObsidian, canObsidian,
                cell -> breakable(self, cell)
                        && (!requireWallsRange.get() || MathUtil.blockVisible(self.getEyePos(), cell.down())));
    }

    /** A target thrown into the air cannot dodge or place back: the moment to build and spam. */
    private static boolean launched(ClientWorld world, LivingEntity target) {
        return !target.isOnGround() && target.getY() - CrystalSolver.floorUnder(world, target) >= 1.0;
    }

    /**
     * Would a crystal in this cell be in attack reach? Keeps a small margin, because the
     * target usually shoves us back a little between the place and the break.
     */
    private boolean breakable(ClientPlayerEntity self, BlockPos cell) {
        // An end crystal's hitbox is 2 x 2 x 2, centred on the cell.
        Box box = new Box(cell.getX() - 0.5, cell.getY(), cell.getZ() - 0.5,
                cell.getX() + 1.5, cell.getY() + 2.0, cell.getZ() + 1.5);
        return HitGeometry.withinReach(self.getEyePos(), box, attackReach(self) - REACH_MARGIN);
    }

    /**
     * Face-place: when the target's effective health (hp + absorption) is below the
     * threshold, every legal blast is worth firing - the min-damage gate collapses
     * to "any damage" exactly like a human face-placing to finish the fight.
     */
    private double effectiveMinDamage(LivingEntity target) {
        double effective = target.getHealth() + target.getAbsorptionAmount();
        if (effective <= facePlaceHealth.get()) {
            return 0.5;
        }
        return minTargetDamage.get();
    }

    private static boolean hasKnockback(net.minecraft.item.ItemStack stack) {
        return stack.getEnchantments().getEnchantments().stream()
                .anyMatch(e -> e.matchesKey(net.minecraft.enchantment.Enchantments.KNOCKBACK));
    }

    private static boolean canSprint(ClientPlayerEntity self) {
        return self.input.hasForwardMovement() && !self.isSneaking() && !self.isUsingItem()
                && !self.horizontalCollision && !self.isTouchingWater()
                && self.getHungerManager().getFoodLevel() > 6
                && !self.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS);
    }

    private void requestRotation(ClientPlayerEntity self, Vec3d aim) {
        // FULLY VISIBLE rotations: the camera genuinely looks at the player before the
        // hit, at the obsidian cell before placing it, at the support before placing
        // the crystal, and at the crystal before breaking it - then back to the
        // player. Nothing is done behind the player's back, so the whole sequence
        // reads as legitimate play to any observer.
        // Every click target is at floor/body height: never look up into the sky.
        double pitch = Math.max(-45.0, MathUtil.pitchTo(self.getEyePos(), aim));
        if (instantAim.get()) {
            // The -45 limit would leave a high click short of its target, so it is not applied.
            RotationManager.snap(self, MathUtil.yawTo(self.getEyePos(), aim), MathUtil.pitchTo(self.getEyePos(), aim));
            return;
        }
        RotationRequest req = RotationRequest
                .to(MathUtil.yawTo(self.getEyePos(), aim), pitch)
                .speed(rotMin.get(), rotMax.get())
                .jitter(0.4)
                .silent(false)
                .validWhile(() -> target != null && target.isAlive())
                .expiresAfter(4)
                .build();
        RotationManager.request(req);
    }

    /** Swaps back to the recorded melee weapon slot (best effort). */
    private void returnToSword(MinecraftClient client) {
        if (meleeHotbarSlot != -1) {
            InventoryManager.selectHotbar(client, meleeHotbarSlot);
        }
    }

    private void aimAt(ClientPlayerEntity self, Vec3d point) {
        requestRotation(self, point);
    }

    // ------------------------------------------------------------------
    // Item access (hotbar first, inventory swap fallback - all vanilla clicks)
    // ------------------------------------------------------------------

    private boolean hasItem(ClientPlayerEntity self, java.util.function.Predicate<net.minecraft.item.ItemStack> pred) {
        return InventoryManager.findHotbarSlot(self, pred) != -1
                || InventoryManager.findHandlerSlot(self, pred) != -1;
    }

    /** Selects the obsidian slot; swaps it into the hotbar from inventory if needed. */
    private boolean ensureObsidianSelected(MinecraftClient client, ClientPlayerEntity self) {
        return ensureItemSelected(client, self, ItemUtil::isObsidian);
    }

    private boolean ensureCrystalSelected(MinecraftClient client, ClientPlayerEntity self) {
        return ensureItemSelected(client, self, ItemUtil::isEndCrystal);
    }

    private boolean ensureItemSelected(MinecraftClient client, ClientPlayerEntity self,
                                      java.util.function.Predicate<net.minecraft.item.ItemStack> pred) {
        int hotbar = InventoryManager.findHotbarSlot(self, pred);
        if (hotbar != -1) {
            return InventoryManager.selectHotbar(client, hotbar);
        }
        int handler = InventoryManager.findHandlerSlot(self, pred);
        if (handler == -1) {
            return false;
        }
        // Exchange the stack into a sacrificial hotbar slot (vanilla SWAP click) -
        // what a player does by dragging inside the inventory, minus the screen
        // opening. The MELEE WEAPON SLOT IS NEVER SACRIFICED, so the sword the loop
        // needs for the next opener always survives the crystal/obsidian swaps.
        int swapSlot = pickSacrificialSlot(self);
        if (swapSlot == -1) {
            return false;
        }
        InventoryManager.swapWithHotbar(client, handler, swapSlot);
        return pred.test(self.getInventory().getStack(swapSlot));
    }

    /** The anchor module that is on and has anchors and glowstone, if any. */
    private AnchorMacro stockedAnchor(ClientPlayerEntity self) {
        for (Module m : dev.zephyr.ZephyrClient.modules().all()) {
            if (m instanceof AnchorMacro anchor && anchor.stocked(self)) {
                return anchor;
            }
        }
        return null;
    }

    /**
     * Whether a jump now would carry us up onto obsidian for no reason: moving into it
     * while the target is not far enough above us to be worth giving up the low ground.
     */
    public boolean blocksJump(boolean forward, boolean back, boolean left, boolean right) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (!isEnabled() || !stayOffObsidian.get() || self == null || world == null || !self.isOnGround()) {
            return false;
        }
        // A jump started before the target is locked can still land on the obsidian, so look
        // at the nearest player a little further out as well.
        LivingEntity foe = target != null && target.isAlive() ? target : world.getPlayers().stream()
                .filter(p -> p != self && p.isAlive() && !p.isSpectator() && self.squaredDistanceTo(p) < 144.0)
                .min(java.util.Comparator.comparingDouble(self::squaredDistanceTo)).orElse(null);
        if (foe == null || foe.getY() - self.getY() >= climbFor.get()) {
            return false;
        }
        int along = (forward ? 1 : 0) - (back ? 1 : 0);
        int across = (left ? 1 : 0) - (right ? 1 : 0);
        if (along == 0 && across == 0) {
            return false;
        }
        double yaw = Math.toRadians(self.getYaw());
        Vec3d move = new Vec3d(-Math.sin(yaw) * along + Math.cos(yaw) * across, 0,
                Math.cos(yaw) * along + Math.sin(yaw) * across).normalize();
        double reach = jumpReach(self, move) + 0.3;
        // Three lines: the hitbox is 0.6 wide and can catch a block with one shoulder.
        for (double side = -0.3; side <= 0.31; side += 0.3) {
            for (double ahead = 0.25; ahead < reach + 0.25; ahead += 0.25) {
                double d = Math.min(ahead, reach);
                BlockPos at = BlockPos.ofFloored(self.getX() + move.x * d - move.z * side, self.getY() + 0.1,
                        self.getZ() + move.z * d + move.x * side);
                if (world.getBlockState(at).isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How far a jump started now travels before it falls back to one block above the ground. */
    private static double jumpReach(ClientPlayerEntity self, Vec3d move) {
        boolean sprint = self.isSprinting() || MinecraftClient.getInstance().options.sprintKey.isPressed();
        Vec3d velocity = self.getVelocity();
        // On the ground the stored velocity is what friction left over; this tick's input adds
        // to it, and a sprinting jump adds a further push.
        double speed = Math.max(0.0, velocity.x * move.x + velocity.z * move.z)
                + (sprint ? 0.128 + 0.2 : 0.098);
        double push = sprint ? 0.026 : 0.02;
        double far = 0;
        double up = 0;
        double rise = 0.42;
        for (int tick = 0; tick < 12 && (rise > 0 || up >= 1.0); tick++) {
            far += speed;
            up += rise;
            rise = (rise - 0.08) * 0.98;
            // The first tick still has ground friction.
            speed = speed * (tick == 0 ? 0.546 : 0.91) + push;
        }
        return far;
    }

    /** Hotbar slot to hand over to inventory swaps: never the melee weapon slot. */
    private int pickSacrificialSlot(ClientPlayerEntity self) {
        // Prefer an empty slot that is not the weapon slot.
        for (int i = 8; i >= 0; i--) {
            if (i != meleeHotbarSlot && self.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        // Then anything that the anchor modules or survival do not rely on.
        for (int i = 8; i >= 0; i--) {
            var held = self.getInventory().getStack(i);
            if (i != meleeHotbarSlot && !ItemUtil.isTotem(held) && !ItemUtil.isGlowstone(held)
                    && !ItemUtil.isRespawnAnchor(held)) {
                return i;
            }
        }
        for (int i = 8; i >= 0; i--) {
            if (i != meleeHotbarSlot) {
                return i;
            }
        }
        return -1;
    }

    private boolean lootNearby(ClientWorld world, LivingEntity target) {
        for (var e : EntityScan.around(world, target.getLerpedPos(1.0F), 2.5)) {
            if (e instanceof net.minecraft.entity.ItemEntity item && item.getItemAge() < 200) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String hudLabel() {
        String tgt = target != null && target.getDisplayName() != null
                ? target.getDisplayName().getString() : "-";
        return String.format("%s · %s · %.0fcps · %s", name, stage.name().toLowerCase(), lastCps, tgt);
    }

    private int meleeHits;
    private int sprintHits;
    private int enchantHits;

    /** Sword hits thrown, how many were sprint hits, and how many used a Knockback weapon. */
    public int[] hitCounts() {
        return new int[]{meleeHits, sprintHits, enchantHits};
    }

    public int detonatedCount() {
        return detonatedCount;
    }

    /** Sword hits that landed without us being hit as well. */
    public int cleanHits() {
        return cleanHits;
    }

    /** Clean hits that were followed by their own obsidian block. */
    public int builtHits() {
        return builtHits;
    }

    /** Crystal cycles started; each is meant to place its own block. */
    public int cycles() {
        return cycles;
    }

    /** Mean ticks from opening a crystal cycle to its first detonation. */
    public double ticksToFirstBlast() {
        return firstBlasts == 0 ? -1 : (double) firstBlastTicks / firstBlasts;
    }

    public int obsidianPlacedCount() {
        return obsidianPlacedCount;
    }
}
