package dev.zephyr.modules.crystal;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.DamageCalculator;
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
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * AutoCrystal - the full place/detonate pipeline.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>DOUBLE_TAP</b>: standard place -> detonate -> immediate re-place rhythm;
 *       after every detonation the place timer re-arms with a near-zero randomized gap.</li>
 *   <li><b>INFINITE</b>: continuous loop, place whenever a legal spot exists and attack
 *       whenever a live crystal qualifies, paced purely by the randomized timers.</li>
 *   <li><b>HIT_ONLY</b> (AutoHitCrystal): detonation only - never places crystals.</li>
 * </ul>
 *
 * <p>Per-tick pipeline: target acquisition -> pause gates (kill / loot) -> parallel best
 * spot (place) and best crystal (attack) search with self-damage + target-damage scoring
 * -> rotation request (silent / smooth / off) -> ms-paced actions at 18-22 CPS gaussian
 * attack intervals. Every placement validates obsidian/bedrock support, air cells, entity
 * overlap, wall range and exposure before a packet is produced.
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

    private final MsTimer attackTimer = new MsTimer();
    private final MsTimer placeTimer = new MsTimer();
    private double lastCps;
    private int placedCount;
    private int detonatedCount;
    private LivingEntity target;

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

        // ---- best attack crystal ------------------------------------------
        EndCrystalEntity bestCrystal = findBestCrystal(client, self, target);

        // ---- best placement spot ------------------------------------------
        BlockPos bestSpot = mode.is("HIT_ONLY") ? null : findBestSpot(client, self, target);

        // ---- rotation ------------------------------------------------------
        Vec3d aimPoint = null;
        if (bestCrystal != null) {
            aimPoint = bestCrystal.getBoundingBox().getCenter();
        } else if (bestSpot != null) {
            aimPoint = Vec3d.ofCenter(bestSpot.up());
        }
        if (aimPoint != null && !rotationMode.is("OFF")) {
            RotationRequest req = RotationRequest
                    .to(MathUtil.yawTo(self.getEyePos(), aimPoint), MathUtil.pitchTo(self.getEyePos(), aimPoint))
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
            double targetDmg = DamageCalculator.crystalDamage(world, bestCrystal.getLerpedPos(1.0F), target);
            double selfDmg = DamageCalculator.crystalDamage(world, bestCrystal.getLerpedPos(1.0F), self);
            if (targetDmg >= minTargetDamage.get()
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
        if (bestSpot != null && placeTimer.isDue()) {
            placeTimer.restart(placeDelayMin.get(), placeDelayMax.get());
            double targetDmg = DamageCalculator.crystalDamage(world, Vec3d.ofCenter(bestSpot.up()), target);
            double selfDmg = DamageCalculator.crystalDamage(world, Vec3d.ofCenter(bestSpot.up()), self);
            if (targetDmg >= minTargetDamage.get() && selfDmg <= maxSelfDamage.get()) {
                int crystalSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isEndCrystal);
                if (crystalSlot != -1 && InventoryManager.selectHotbar(client, crystalSlot)) {
                    BlockHitResult hit = RaycastValidator.placementHit(bestSpot);
                    if (CombatActions.interactBlock(client, hit).isAccepted()) {
                        placedCount++;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Searches
    // ------------------------------------------------------------------

    private EndCrystalEntity findBestCrystal(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        ClientWorld world = client.world;
        EndCrystalEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double rangeSq = attackRange.get() * attackRange.get();
        for (var e : world.getEntities()) {
            if (!(e instanceof EndCrystalEntity crystal) || !crystal.isAlive()) {
                continue;
            }
            if (self.squaredDistanceTo(crystal) > rangeSq) {
                continue;
            }
            if (!RaycastValidator.hitboxVisible(self.getEyePos(), crystal.getBoundingBox(), self)) {
                continue;
            }
            double targetDmg = DamageCalculator.crystalDamage(world, crystal.getLerpedPos(1.0F), target);
            double selfDmg = DamageCalculator.crystalDamage(world, crystal.getLerpedPos(1.0F), self);
            if (targetDmg < minTargetDamage.get() || selfDmg > maxSelfDamage.get()) {
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

    private BlockPos findBestSpot(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        ClientWorld world = client.world;
        BlockPos center = target.getBlockPos();
        int r = (int) Math.ceil(placeRange.get());
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPos pos : BlockPos.iterateOutwards(center, r, r, r)) {
            if (self.getBlockPos().getManhattanDistance(pos) > placeRange.get().intValue()) {
                continue;
            }
            if (!RaycastValidator.canPlaceCrystal(world, pos, self, true, !requireWallsRange.get())) {
                continue;
            }
            Vec3d blastPos = Vec3d.ofCenter(pos.up());
            double targetDmg = DamageCalculator.crystalDamage(world, blastPos, target);
            double selfDmg = DamageCalculator.crystalDamage(world, blastPos, self);
            if (targetDmg < minTargetDamage.get() || selfDmg > maxSelfDamage.get()) {
                continue;
            }
            double score = targetDmg * 2.0 - selfDmg * 1.5 - self.getBlockPos().getManhattanDistance(pos);
            if (score > bestScore) {
                bestScore = score;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private boolean lootNearby(ClientWorld world, LivingEntity target) {
        double radiusSq = lootRadius.get() * lootRadius.get();
        for (var e : world.getEntities()) {
            if (e instanceof ItemEntity item
                    && item.squaredDistanceTo(target) <= radiusSq
                    && item.getItemAge() < 200) {
                return true;
            }
        }
        return false;
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
