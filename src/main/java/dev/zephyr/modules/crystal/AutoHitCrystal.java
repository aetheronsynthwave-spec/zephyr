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
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * AutoHitCrystal - the full engagement loop, on autopilot:
 *
 * <pre>  MELEE hit with ANY item (cooldown-aware, so swords/delayed weapons are respected)
 *     |   (the instant the hit lands)
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
    private final NumberSetting minCooldown = num("cooldown", "Min attack cooldown", 0.5, 1.0, 0.05, 0.85);
    private final NumberSetting maxHurtTime = num("hurtTime", "Max target hurt time", 0, 10, 1, 0);
    private final NumberSetting placeRange = num("placeRange", "Solve range", 2.0, 6.0, 0.1, 4.5);
    private final NumberSetting attackRange = num("attackRange", "Crystal attack range", 2.0, 6.0, 0.1, 4.0);
    private final NumberSetting reEngageRange = num("reRange", "Re-engage range", 2.0, 8.0, 0.1, 5.0);
    private final NumberSetting minTargetDamage = num("minDmg", "Min target dmg", 0.0, 20.0, 0.5, 3.0);
    private final NumberSetting maxSelfDamage = num("maxSelf", "Max self dmg", 0.0, 20.0, 0.5, 8.0);
    private final NumberSetting facePlaceHealth = num("faceplace", "Faceplace below hp", 0.0, 12.0, 0.5, 3.0);
    private final NumberSetting cpsMin = num("cpsMin", "CPS min", 4, 30, 0.5, 8);
    private final NumberSetting cpsMax = num("cpsMax", "CPS max", 4, 30, 0.5, 12);
    private final NumberSetting swapMin = num("swapMin", "Swap delay min ms", 5, 100, 1, 35);
    private final NumberSetting swapMax = num("swapMax", "Swap delay max ms", 5, 150, 1, 70);
    private final NumberSetting rotMin = num("rotMin", "Rotation min deg/t", 5, 120, 5, 18);
    private final NumberSetting rotMax = num("rotMax", "Rotation max deg/t", 5, 180, 5, 40);

    private final BooleanSetting useObsidian = bool("obsidian", "Place obsidian supports", true);
    /** Always open with our own obsidian instead of grabbing whatever obsidian is lying around. */
    private final BooleanSetting ownObsidian = bool("ownObsidian", "Prefer own obsidian", true);
    private final NumberSetting aimTolerance = num("aimTol", "Aim tolerance deg", 3, 30, 0.5, 10);
    private final BooleanSetting predictTarget = bool("predict", "Predict target motion", true);
    private final BooleanSetting requireWallsRange = bool("walls", "Respect walls", false);
    private final BooleanSetting pauseOnLoot = bool("pauseLoot", "Pause near loot", false);

    private enum Stage { MELEE, OBSIDIAN, SPAM }

    /** Out-of-crystals / unsolvable backoff before folding back to the sword. */
    private static final int NO_CRYSTAL_GIVE_UP_TICKS = 20;

    private Stage stage = Stage.MELEE;
    private final MsTimer stageTimer = new MsTimer();
    private final MsTimer attackTimer = new MsTimer();
    private LivingEntity target;
    private Spot plan;
    private int meleeHotbarSlot = -1;
    private int failTicks;
    private int sinceSolve;
    private double lastCps;
    private int detonatedCount;
    private int obsidianPlacedCount;

    /** Re-solve backoff: when nothing is solvable, retry at most every 4 ticks. */
    private static final int SOLVE_BACKOFF = 4;

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
        failTicks = 0;
        sinceSolve = 0;
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
            return;
        }
        if (pauseOnLoot.get() && lootNearby(world, target)) {
            return;
        }

        double dist = self.distanceTo(target);

        switch (stage) {
            case MELEE -> meleePhase(client, self, world, target, dist);
            case OBSIDIAN -> obsidianPhase(client, self, world, target, dist);
            case SPAM -> spamPhase(client, self, world, target, dist);
        }
    }

    // ------------------------------------------------------------------
    // Phase 1: melee opener with any item
    // ------------------------------------------------------------------

    private void meleePhase(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                            LivingEntity target, double dist) {
        // THE SWORD COMES FIRST: every melee hit is thrown with the recorded weapon
        // slot. If the previous crystal cycle left crystals/obsidian in hand, the
        // module visibly swaps back to the sword BEFORE the swing - and the next
        // crystal phase never destroys that slot (see ensureItemSelected).
        if (meleeHotbarSlot != -1
                && InventoryManager.selectedHotbar(self) != meleeHotbarSlot) {
            if (!InventoryManager.selectHotbar(client, meleeHotbarSlot)) {
                meleeHotbarSlot = InventoryManager.selectedHotbar(self);
            }
            return; // give the swap a tick to land server-side
        }

        // Leftover crystal from a previous cycle? It is free damage - take it.
        if (tryAttackBestCrystal(client, self, world, target)) {
            return;
        }

        if (dist > meleeRange.get()) {
            return; // not in sword range yet
        }
        if (target.hurtTime > maxHurtTime.get().intValue()) {
            return; // server invulnerability frames
        }
        // Weapon-delay aware: swords/axes carry a full 1.0-scaled cooldown; respecting
        // it keeps every hit at full strength and the rhythm legal.
        if (ItemUtil.attackCooldownProgress(self) < minCooldown.get()) {
            return;
        }

        Vec3d aim = MathUtil.aimPoint(target);
        requestRotation(self, aim);
        if (!RotationManager.isAimedAt(aim, aimTolerance.get())) {
            return;
        }

        if (CombatActions.attackEntity(client, target)) {
            // THE TRIGGER MOMENT: solve the obsidian/crystal placement right now.
            meleeHotbarSlot = InventoryManager.selectedHotbar(self);
            Spot solved = solve(client, self, world, target);
            if (solved == null) {
                return; // nothing to gain from crystals; keep swinging
            }
            plan = solved;
            meleeHotbarSlot = InventoryManager.selectedHotbar(self);
            failTicks = 0;
            if (solved.needsObsidian() && useObsidian.get() && hasItem(self, ItemUtil::isObsidian)) {
                stage = Stage.OBSIDIAN;
                stageTimer.restart(2.0, 9.0); // "very moment the hit is triggered"
            } else {
                stage = Stage.SPAM;
                stageTimer.restart(0.0, 8.0);
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 2: obsidian support placement (when solved spot has no support)
    // ------------------------------------------------------------------

    private void obsidianPhase(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                               LivingEntity target, double dist) {
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
        // Loop exit: target DOWN or OUT OF RANGE -> swap back to the sword first,
        // then fold back to the melee phase automatically.
        if (!target.isAlive() || dist > reEngageRange.get()) {
            stage = Stage.MELEE;
            plan = null;
            returnToSword(client);
            return;
        }

        // Attack the best live crystal at the randomized CPS rhythm.
        if (tryAttackBestCrystal(client, self, world, target)) {
            return;
        }

        // No live crystal in range: (re)place on the plan support.
        if (!stageTimer.isDue()) {
            return;
        }
        if (!hasItem(self, ItemUtil::isEndCrystal)) {
            // Out of crystals: stop burning ticks in this phase - swap back to the
            // sword and let the melee loop finish the fight (or re-trigger later).
            if (++failTicks > NO_CRYSTAL_GIVE_UP_TICKS) {
                stage = Stage.MELEE;
                failTicks = 0;
                returnToSword(client);
            }
            return;
        }

        // Re-solve when the plan is no longer usable; keep the plan otherwise (the
        // crystal cell does not move, so reuse is free quality). When a solve comes
        // back empty, back off a few ticks instead of rescanning every single tick.
        Spot solved;
        if (plan != null && spotStillValid(world, self, plan)) {
            solved = plan;
        } else if (sinceSolve < SOLVE_BACKOFF) {
            solved = null;
        } else {
            solved = solve(client, self, world, target);
            sinceSolve = 0;
        }
        sinceSolve++;
        if (solved == null) {
            failTicks++;
            if (failTicks > 40) {
                stage = Stage.MELEE; // no geometry worth crystals - back to sword
                failTicks = 0;
                returnToSword(client);
            }
            return;
        }
        plan = solved;
        failTicks = 0;

        if (!ensureCrystalSelected(client, self)) {
            return;
        }
        aimAt(self, Vec3d.ofCenter(plan.support));
        if (!RotationManager.isAimedAt(Vec3d.ofCenter(plan.support), aimTolerance.get())) {
            return;
        }
        BlockHitResult hit = CrystalSolver.crystalPlacementHit(world, plan.support, self.getEyePos());
        if (hit != null && CombatActions.interactBlock(client, hit).isAccepted()) {
            // Double-tap rhythm: as soon as the crystal entity exists, the attack timer
            // detonates it within the next few ms.
            attackTimer.restartCps(lastCps = RandomController.uniform(cpsMax.get(), cpsMax.get() + 4), 15);
            stageTimer.restart(swapMin.get(), swapMax.get());
        }
    }

    // ------------------------------------------------------------------
    // Shared actions
    // ------------------------------------------------------------------

    /** Detonates the highest-value live crystal; true when an attack went out. */
    private boolean tryAttackBestCrystal(MinecraftClient client, ClientPlayerEntity self,
                                         ClientWorld world, LivingEntity target) {
        EndCrystalEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double rangeSq = attackRange.get() * attackRange.get();
        Vec3d predicted = predictTarget.get()
                ? CrystalSolver.predictPos(target, 1) : target.getLerpedPos(1.0F);
        for (var e : EntityScan.around(world, self.getLerpedPos(1.0F), attackRange.get() + 1.0)) {
            if (!(e instanceof EndCrystalEntity crystal) || !crystal.isAlive()) {
                continue;
            }
            if (self.squaredDistanceTo(crystal) > rangeSq) {
                continue;
            }
            if (!RaycastValidator.hitboxVisible(self.getEyePos(), crystal.getBoundingBox(), self)) {
                continue;
            }
            double targetDmg = dev.zephyr.core.combat.DamageCalculator.explosionDamage(
                    world, crystal.getLerpedPos(1.0F), dev.zephyr.core.combat.DamageCalculator.CRYSTAL_STRENGTH,
                    predicted, target);
            double selfDmg = DamageCalculator.crystalDamage(world, crystal.getLerpedPos(1.0F), self);
            if (targetDmg < effectiveMinDamage(target) || selfDmg > maxSelfDamage.get()) {
                continue;
            }
            double score = targetDmg * 2.0 - selfDmg - self.squaredDistanceTo(crystal) * 0.1;
            if (score > bestScore) {
                bestScore = score;
                best = crystal;
            }
        }
        if (best == null || !attackTimer.isDue()) {
            return false;
        }
        Vec3d aim = best.getBoundingBox().getCenter();
        requestRotation(self, aim);
        if (!RotationManager.isAimedAt(aim, aimTolerance.get())) {
            return true; // rotation still converging; keep the timer armed
        }
        attackTimer.restartCps(lastCps = RandomController.uniform(cpsMin.get(), cpsMax.get()), 15);
        if (CombatActions.attackEntity(client, best)) {
            detonatedCount++;
            // Immediate re-place rhythm right after a detonation.
            stageTimer.restart(0.0, 8.0);
        }
        return true;
    }

    /** The solve: best natural spot, else the best obsidian plan. */
    private Spot solve(MinecraftClient client, ClientPlayerEntity self, ClientWorld world,
                       LivingEntity target) {
        int predictTicks = predictTarget.get() ? 2 : 0;
        boolean walls = requireWallsRange.get();
        boolean canObsidian = useObsidian.get() && hasItem(self, ItemUtil::isObsidian);
        if (canObsidian && ownObsidian.get()) {
            // Own obsidian first: a fresh block right at the target's feet, placed by us,
            // instead of locking onto some random obsidian that happens to be nearby.
            Spot own = CrystalSolver.findBestObsidianPlan(world, self, target, placeRange.get(),
                    effectiveMinDamage(target), maxSelfDamage.get(), walls, predictTicks);
            if (own != null) {
                return own;
            }
        }
        Spot natural = CrystalSolver.findBestCrystalSpot(world, self, target, placeRange.get(),
                effectiveMinDamage(target), maxSelfDamage.get(), walls, predictTicks);
        if (natural != null || !canObsidian) {
            return natural;
        }
        return CrystalSolver.findBestObsidianPlan(world, self, target, placeRange.get(),
                effectiveMinDamage(target), maxSelfDamage.get(), walls, predictTicks);
    }

    /** True while a solved plan is still physically usable right now. */
    private static boolean spotStillValid(ClientWorld world, ClientPlayerEntity self, Spot s) {
        if (s.needsObsidian()) {
            var st = world.getBlockState(s.obsidianCell);
            if (st.isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
                return true; // obsidian staged -> spam phase can place on it directly
            }
            if (!st.isAir()) {
                return false;
            }
        } else if (!world.getBlockState(s.support).isOf(net.minecraft.block.Blocks.OBSIDIAN)
                && !world.getBlockState(s.support).isOf(net.minecraft.block.Blocks.BEDROCK)) {
            return false;
        }
        return CrystalSolver.crystalCellLegal(world, s.crystalCell, self);
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

    private void requestRotation(ClientPlayerEntity self, Vec3d aim) {
        // FULLY VISIBLE rotations: the camera genuinely looks at the player before the
        // hit, at the obsidian cell before placing it, at the support before placing
        // the crystal, and at the crystal before breaking it - then back to the
        // player. Nothing is done behind the player's back, so the whole sequence
        // reads as legitimate play to any observer.
        RotationRequest req = RotationRequest
                .to(MathUtil.yawTo(self.getEyePos(), aim), MathUtil.pitchTo(self.getEyePos(), aim))
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

    /** Hotbar slot to hand over to inventory swaps: never the melee weapon slot. */
    private int pickSacrificialSlot(ClientPlayerEntity self) {
        // Prefer an empty slot that is not the weapon slot.
        for (int i = 8; i >= 0; i--) {
            if (i != meleeHotbarSlot && self.getInventory().getStack(i).isEmpty()) {
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

    public int detonatedCount() {
        return detonatedCount;
    }

    public int obsidianPlacedCount() {
        return obsidianPlacedCount;
    }
}
