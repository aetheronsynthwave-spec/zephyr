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
import dev.zephyr.core.settings.ModeSetting;
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
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

import java.util.List;
/**
 * AutoCrystal - the full place/detonate pipeline, solver-driven.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>DOUBLE_TAP</b>: standard place -> detonate -> immediate re-place rhythm;
 *       after every detonation the place timer re-arms with a near-zero randomized gap.</li>
 *   <li><b>INFINITE</b>: continuous loop, place whenever a legal spot exists and attack
 *       whenever a live crystal qualifies, paced purely by the randomized timers.</li>
 *   <li><b>HIT_ONLY</b>: detonation only - never places crystals.</li>
 * </ul>
 *
 * <p>Per-tick pipeline: target acquisition -> pause gates (kill / loot) -> solver query
 * ({@link CrystalSolver}: full-sphere damage scan with motion prediction, face-aware
 * click construction, optional obsidian-support planning) -> best-crystal search ->
 * rotation request (silent / smooth / off) -> ms-paced actions at 18-22 CPS gaussian
 * attack intervals. Face-place: below the health threshold the min-damage gate
 * collapses so every legal blast is taken to finish the fight.
 */
public final class AutoCrystal extends Module {

    private final ModeSetting mode = s(new ModeSetting("mode", "Mode",
            List.of("DOUBLE_TAP", "INFINITE", "HIT_ONLY"), "DOUBLE_TAP"));
    private final ModeSetting rotationMode = s(new ModeSetting("rotation", "Rotation",
            List.of("SILENT", "SMOOTH", "OFF"), "SILENT"));

    private final NumberSetting placeRange = num("placeRange", "Place range", 2.0, 6.0, 0.1, 4.2);
    private final NumberSetting attackRange = num("attackRange", "Attack range", 2.0, 6.0, 0.1, 4.0);
    private final NumberSetting minTargetDamage = num("minDmg", "Min target dmg", 0.0, 20.0, 0.5, 4.0);
    private final NumberSetting maxSelfDamage = num("maxSelf", "Max self dmg", 0.0, 20.0, 0.5, 6.0);
    private final NumberSetting facePlaceHealth = num("faceplace", "Faceplace below hp", 0.0, 12.0, 0.5, 3.0);
    private final NumberSetting cpsMin = num("cpsMin", "CPS min", 10, 30, 0.5, 18);
    private final NumberSetting cpsMax = num("cpsMax", "CPS max", 10, 30, 0.5, 22);
    private final NumberSetting placeDelayMin = num("placeMin", "Place delay min ms", 0, 200, 2, 0);
    private final NumberSetting placeDelayMax = num("placeMax", "Place delay max ms", 0, 300, 2, 25);
    private final NumberSetting maxTargetHurtTime = num("hurtTime", "Max target hurt time", 0, 10, 1, 0);
    private final NumberSetting rotMin = num("rotMin", "Rotation min deg/t", 5, 120, 5, 30);
    private final NumberSetting rotMax = num("rotMax", "Rotation max deg/t", 5, 180, 5, 70);
    private final NumberSetting jitter = num("jitter", "Rotation jitter", 0.0, 2.0, 0.05, 0.4);
    private final NumberSetting lootRadius = num("lootRadius", "Loot pause radius", 1.0, 5.0, 0.5, 2.5);

    private final BooleanSetting pauseOnKill = bool("pauseOnKill", "Pause on kill", true);
    private final BooleanSetting pauseOnLoot = bool("pauseOnLoot", "Pause near loot", true);
    private final BooleanSetting requireWallsRange = bool("walls", "Respect walls", false);
    private final BooleanSetting obsidianSupport = bool("obsidian", "Place obsidian supports", true);
    private final BooleanSetting predictTarget = bool("predict", "Predict target motion", true);

    private final MsTimer attackTimer = new MsTimer();
    private final MsTimer placeTimer = new MsTimer();
    private double lastCps;
    private int placedCount;
    private int detonatedCount;
    private LivingEntity target;

    /** Solver result cache - see {@link #solveCached}. */
    private Spot cachedSpot;
    private LivingEntity spotTarget;
    private Vec3d spotAnchor;
    private int spotAge;
    /** Re-solve backoff when no legal spot exists (full scans are not free). */
    private static final int SOLVE_INTERVAL = 3;

    @Override
    protected boolean conflicts(Module other) {
        return super.conflicts(other) || other instanceof AnchorMacro;
    }

    @Override
    protected String exclusiveGroup() {
        return "crystal";
    }

    public AutoCrystal() {
        super("AutoCrystal", ModuleCategory.CRYSTAL, 0);
    }

    @Override
    protected void onEnable() {
        attackTimer.restartCps(cpsMin.get(), 15);
        placeTimer.restart(placeDelayMin.get(), placeDelayMax.get());
    }

    @Override
    protected void onDisable() {
        RotationManager.clear(0);
        target = null;
        cachedSpot = null;
        spotTarget = null;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (self == null || world == null) {
            return;
        }

        double searchRange = Math.max(placeRange.get(), attackRange.get()) + 1.0;
        TargetCriteria criteria = new TargetCriteria().range(searchRange);
        target = TargetManager.acquire(client, criteria);
        TargetManager.cleanup();
        if (target == null) {
            return;
        }

        // ---- pause gates -------------------------------------------------
        if (pauseOnKill.get() && (!target.isAlive() || target.getHealth() <= 0.0f)) {
            TargetManager.release();
            return;
        }
        if (pauseOnLoot.get() && lootNearby(world, target)) {
            return;
        }

        int predictTicks = predictTarget.get() ? 2 : 0;
        double minDmg = effectiveMinDamage(target);

        // ---- best attack crystal ------------------------------------------
        EndCrystalEntity bestCrystal = findBestCrystal(client, self, world, target, minDmg, predictTicks);

        // ---- best placement spot (solver, cached) ---------------------------
        Spot spot = mode.is("HIT_ONLY") ? null
                : solveCached(self, world, target, minDmg, predictTicks);

        // ---- rotation ------------------------------------------------------
        Vec3d aimPoint = null;
        if (bestCrystal != null) {
            aimPoint = bestCrystal.getBoundingBox().getCenter();
        } else if (spot != null) {
            aimPoint = Vec3d.ofCenter(spot.support);
        }
        if (aimPoint != null && !rotationMode.is("OFF")) {
            RotationRequest req = RotationRequest
                    .to(MathUtil.yawTo(self.getEyePos(), aimPoint),
                            MathUtil.pitchTo(self.getEyePos(), aimPoint))
                    .speed(rotMin.get(), rotMax.get())
                    .jitter(jitter.get())
                    .silent(rotationMode.is("SILENT"))
                    .validWhile(target::isAlive)
                    .expiresAfter(4)
                    .build();
            RotationManager.request(req);
        }

        // ---- detonate ------------------------------------------------------
        if (bestCrystal != null && attackTimer.isDue()) {
            attackTimer.restartCps(lastCps = RandomController.uniform(cpsMin.get(), cpsMax.get()), 15);
            Vec3d blast = bestCrystal.getLerpedPos(1.0F);
            double targetDmg = DamageCalculator.explosionDamage(world, blast,
                    DamageCalculator.CRYSTAL_STRENGTH, predictedPos(target, predictTicks), target);
            double selfDmg = DamageCalculator.crystalDamage(world, blast, self);
            if (targetDmg >= minDmg
                    && selfDmg <= maxSelfDamage.get()
                    && target.hurtTime <= maxTargetHurtTime.get().intValue()
                    && RotationManager.isAimedAt(bestCrystal.getBoundingBox().getCenter(), 8.0)) {
                if (CombatActions.attackEntity(client, bestCrystal)) {
                    detonatedCount++;
                    if (mode.is("DOUBLE_TAP")) {
                        // Immediate re-place rhythm: near-zero randomized place gap.
                        placeTimer.restart(0, 8);
                    }
                }
            }
        }

        // ---- place ----------------------------------------------------------
        if (spot != null && placeTimer.isDue()) {
            placeTimer.restart(placeDelayMin.get(), placeDelayMax.get());
            Vec3d blast = Vec3d.ofCenter(spot.crystalCell);
            double targetDmg = spot.targetDamage;
            double selfDmg = spot.selfDamage;
            if (targetDmg >= minDmg && selfDmg <= maxSelfDamage.get()) {
                int crystalSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isEndCrystal);
                if (crystalSlot != -1 && InventoryManager.selectHotbar(client, crystalSlot)) {
                    // Face-aware click: the support face point closest to the eye.
                    BlockHitResult hit = CrystalSolver.crystalPlacementHit(
                            world, spot.support, self.getEyePos());
                    if (hit != null && CombatActions.interactBlock(client, hit).isAccepted()) {
                        placedCount++;
                    }
                } else if (obsidianSupport.get() && spot.needsObsidian()
                        && placeObsidianForSpot(client, self, world, spot)) {
                    placedCount++; // obsidian staged; the crystal lands next cycle
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Searches
    // ------------------------------------------------------------------

    /**
     * Cached solve: the full scan runs when it is genuinely stale (target switched,
     * target moved over 0.75 blocks, every {@value #SOLVE_INTERVAL} ticks, or the
     * cached plan became illegal). Between those events the cached plan is reused -
     * a crystal cell does not move, so placement quality is identical while the
     * per-tick raycast cost drops to near zero. This cache plus the two-phase
     * solver are what fixed the periodic multi-hundred-ms freezes.
     */
    private Spot solveCached(ClientPlayerEntity self, ClientWorld world, LivingEntity target,
                             double minDmg, int predictTicks) {
        Vec3d anchor = predictTicks > 0
                ? CrystalSolver.predictPos(target, predictTicks) : target.getLerpedPos(1.0F);
        boolean moved = spotAnchor != null && spotAnchor.squaredDistanceTo(anchor) > 0.5625;
        if (cachedSpot != null && spotTarget == target && !moved && spotAge < SOLVE_INTERVAL
                && spotStillValid(world, self, cachedSpot)) {
            spotAge++;
            return cachedSpot;
        }
        Spot spot = CrystalSolver.findBestSpotWithSupport(world, self, target, placeRange.get(),
                minDmg, maxSelfDamage.get(), requireWallsRange.get(), predictTicks, obsidianSupport.get());
        cachedSpot = spot;
        spotTarget = target;
        spotAnchor = anchor;
        spotAge = 0;
        return spot;
    }

    /** True while a cached plan is still physically usable right now. */
    private static boolean spotStillValid(ClientWorld world, ClientPlayerEntity self, Spot s) {
        if (s.needsObsidian()) {
            var st = world.getBlockState(s.obsidianCell);
            if (st.isOf(net.minecraft.block.Blocks.OBSIDIAN)) {
                return true; // staged obsidian landed -> the crystal phase takes over
            }
            if (!st.isAir()) {
                return false; // cell filled by something else
            }
        } else if (!world.getBlockState(s.support).isOf(net.minecraft.block.Blocks.OBSIDIAN)
                && !world.getBlockState(s.support).isOf(net.minecraft.block.Blocks.BEDROCK)) {
            return false;
        }
        return CrystalSolver.crystalCellLegal(world, s.crystalCell, self);
    }

    private EndCrystalEntity findBestCrystal(MinecraftClient client, ClientPlayerEntity self,
                                             ClientWorld world, LivingEntity target,
                                             double minDmg, int predictTicks) {
        EndCrystalEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double rangeSq = attackRange.get() * attackRange.get();
        Vec3d predicted = predictedPos(target, predictTicks);
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
            Vec3d blast = crystal.getLerpedPos(1.0F);
            double targetDmg = DamageCalculator.explosionDamage(world, blast,
                    DamageCalculator.CRYSTAL_STRENGTH, predicted, target);
            double selfDmg = DamageCalculator.crystalDamage(world, blast, self);
            if (targetDmg < minDmg || selfDmg > maxSelfDamage.get()) {
                continue;
            }
            double score = targetDmg * 2.0 - selfDmg - Math.sqrt(self.squaredDistanceTo(crystal));
            if (score > bestScore) {
                bestScore = score;
                best = crystal;
            }
        }
        return best;
    }

    /**
     * Obsidian staging for a solver spot that lacks support: swap to obsidian, place
     * at the planned cell via its solved support face, then re-select the crystal slot
     * next cycle. Vanilla clicks only, ms-paced by the existing place timer.
     */
    private boolean placeObsidianForSpot(MinecraftClient client, ClientPlayerEntity self,
                                         ClientWorld world, Spot spot) {
        if (!spot.needsObsidian() || spot.obsidianHit == null) {
            return false;
        }
        int obsidianSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isObsidian);
        if (obsidianSlot == -1) {
            return false;
        }
        if (InventoryManager.selectHotbar(client, obsidianSlot)) {
            return CombatActions.interactBlock(client, spot.obsidianHit).isAccepted();
        }
        return false;
    }

    private boolean lootNearby(ClientWorld world, LivingEntity target) {
        double radiusSq = lootRadius.get() * lootRadius.get();
        for (var e : EntityScan.around(world, target.getLerpedPos(1.0F), lootRadius.get())) {
            if (e instanceof ItemEntity item
                    && item.squaredDistanceTo(target) <= radiusSq
                    && item.getItemAge() < 200) {
                return true;
            }
        }
        return false;
    }

    /** Face-place gate: below the health threshold any blast is worth firing. */
    private double effectiveMinDamage(LivingEntity target) {
        double effective = target.getHealth() + target.getAbsorptionAmount();
        if (effective <= facePlaceHealth.get()) {
            return 0.5;
        }
        return minTargetDamage.get();
    }

    private Vec3d predictedPos(LivingEntity target, int predictTicks) {
        return predictTicks > 0 ? CrystalSolver.predictPos(target, predictTicks)
                : target.getLerpedPos(1.0F);
    }

    @Override
    public String hudLabel() {
        String tgt = target != null && target.getDisplayName() != null
                ? target.getDisplayName().getString() : "-";
        return String.format("%s · %s · %.1fcps · %s", name, mode.get(), lastCps, tgt);
    }

    public int placedCount() {
        return placedCount;
    }

    public int detonatedCount() {
        return detonatedCount;
    }
}
