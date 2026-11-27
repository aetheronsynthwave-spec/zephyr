package dev.zephyr.modules.crystal;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.DamageCalculator;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.rotation.RotationManager;
import dev.zephyr.core.rotation.RotationRequest;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.target.TargetManager;
import dev.zephyr.core.target.TargetCriteria;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.util.MathUtil;
import dev.zephyr.core.world.RaycastValidator;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * AnchorMacro - respawn-anchor detonation chain executed as a strict state machine:
 *
 * <pre>IDLE -> PLACE (anchor onto solid support) -> CHARGE (glowstone on the anchor)
 *           -> DETONATE (right-click on the charged anchor) -> COOLDOWN -> IDLE</pre>
 *
 * <p>Stage transitions are paced 3-11 ms (gaussian, randomized each transition) so the
 * chain lands inside a single client tick group exactly like a hand-executed macro.
 * Placement spots are scored by anchor damage to the target minus self-damage exposure;
 * detonation is gated on rotation convergence (silent packets) and the target being
 * inside the blast radius.
 */
public class AnchorMacro extends Module {

    protected final NumberSetting paceMin = num("paceMin", "Stage delay min ms", 3, 50, 1, 3);
    protected final NumberSetting paceMax = num("paceMax", "Stage delay max ms", 3, 80, 1, 11);
    protected final NumberSetting range = num("range", "Range", 2.0, 6.0, 0.1, 4.5);
    protected final NumberSetting maxSelfDamage = num("maxSelf", "Max self dmg", 0.0, 20.0, 0.5, 8.0);
    protected final NumberSetting minTargetDamage = num("minDmg", "Min target dmg", 0.0, 20.0, 0.5, 5.0);
    protected final NumberSetting rotMin = num("rotMin", "Rotation min deg/t", 10, 120, 5, 40);
    protected final NumberSetting rotMax = num("rotMax", "Rotation max deg/t", 10, 180, 5, 80);
    private final BooleanSetting requireTargetNear = bool("needTarget", "Require target in blast", true);

    protected enum Stage { IDLE, PLACE, CHARGE, DETONATE, COOLDOWN }

    protected Stage stage = Stage.IDLE;
    protected final MsTimer stageTimer = new MsTimer();
    protected BlockPos anchorPos;
    protected int stageTicks;

    public AnchorMacro() {
        this("AnchorMacro");
    }

    protected AnchorMacro(String name) {
        super(name, ModuleCategory.CRYSTAL, 0);
    }

    /** Overridden by SafeAnchor. */
    protected boolean protectivePlacement() {
        return false;
    }

    @Override
    protected void onDisable() {
        stage = Stage.IDLE;
        stageTimer.invalidate();
        anchorPos = null;
        RotationManager.clear(0);
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (self == null || world == null) {
            stage = Stage.IDLE;
            return;
        }
        stageTicks++;

        LivingEntity target = TargetManager.acquire(client, new TargetCriteria().range(range.get() + 1.0));
        if (target == null || !target.isAlive()) {
            if (stage != Stage.IDLE) {
                stage = Stage.IDLE;
                stageTimer.invalidate();
            }
            return;
        }

        switch (stage) {
            case IDLE -> {
                anchorPos = findAnchorSpot(client, self, target);
                if (anchorPos != null) {
                    aimAt(self, anchorPos);
                    if (RotationManager.isAimedAt(Vec3d.ofCenter(anchorPos.up()), 10.0)
                            && InventoryManager.selectHotbar(client,
                            InventoryManager.findHotbarSlot(self, ItemUtil::isRespawnAnchor))) {
                        stage = Stage.PLACE;
                        stageTimer.restart(paceMin.get(), paceMax.get());
                        stageTicks = 0;
                    }
                }
            }
            case PLACE -> {
                if (anchorPos == null || !stageTimer.isDue()) {
                    return;
                }
                BlockHitResult hit = RaycastValidator.placementHit(anchorPos.down());
                CombatActions.interactBlock(client, hit);
                if (world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                    // Charged? Vanilla anchors spawn at charge 0; charge with glowstone next.
                    if (InventoryManager.selectHotbar(client,
                            InventoryManager.findHotbarSlot(self, ItemUtil::isGlowstone))) {
                        stage = Stage.CHARGE;
                        stageTimer.restart(paceMin.get(), paceMax.get());
                        stageTicks = 0;
                    }
                } else if (stageTicks > 6) {
                    stage = Stage.IDLE;
                    stageTimer.invalidate();
                }
            }
            case CHARGE -> {
                if (anchorPos == null || !stageTimer.isDue()) {
                    return;
                }
                BlockHitResult hit = anchorFaceHit(anchorPos);
                CombatActions.interactBlock(client, hit);
                var state = world.getBlockState(anchorPos);
                if (state.isOf(Blocks.RESPAWN_ANCHOR)
                        && state.get(Properties.CHARGES) >= 1) {
                    // Swap off glowstone so the next click detonates instead of charging.
                    if (!ItemUtil.isGlowstone(self.getMainHandStack())
                            || InventoryManager.selectHotbar(client,
                            InventoryManager.findHotbarSlot(self, s -> !ItemUtil.isGlowstone(s)))) {
                        stage = Stage.DETONATE;
                        stageTimer.restart(paceMin.get(), paceMax.get());
                        stageTicks = 0;
                    }
                } else if (stageTicks > 6) {
                    stage = Stage.IDLE;
                    stageTimer.invalidate();
                }
            }
            case DETONATE -> {
                if (anchorPos == null || !stageTimer.isDue()) {
                    return;
                }
                var state = world.getBlockState(anchorPos);
                if (!state.isOf(Blocks.RESPAWN_ANCHOR)) {
                    stage = Stage.IDLE;
                    stageTimer.invalidate();
                    return;
                }
                Vec3d blast = Vec3d.ofCenter(anchorPos);
                double selfDmg = DamageCalculator.anchorDamage(world, blast, self);
                double targetDmg = DamageCalculator.anchorDamage(world, blast, target);
                boolean targetNear = target.getLerpedPos(1.0F).distanceTo(blast) <= 5.0;
                if (!protectivePlacement() && selfDmg > maxSelfDamage.get()) {
                    stage = Stage.IDLE;
                    stageTimer.invalidate();
                    return;
                }
                if (targetDmg < minTargetDamage.get() || (requireTargetNear.get() && !targetNear)) {
                    stage = Stage.IDLE;
                    stageTimer.invalidate();
                    return;
                }
                if (protectivePlacement() && selfDmg > maxSelfDamage.get()) {
                    // SafeAnchor hook point: place protective cover before detonating.
                    if (!placeProtection(client, self, anchorPos)) {
                        return; // wait a few more paced attempts
                    }
                }
                if (RotationManager.isAimedAt(blast, 10.0)) {
                    CombatActions.interactBlock(client, anchorFaceHit(anchorPos));
                    stage = Stage.COOLDOWN;
                    stageTimer.restart(paceMin.get() * 4, paceMax.get() * 4);
                    stageTicks = 0;
                    anchorPos = null;
                }
            }
            case COOLDOWN -> {
                if (stageTimer.isDue()) {
                    stage = Stage.IDLE;
                    stageTimer.invalidate();
                }
            }
        }
    }

    /** Best support position near the target for an anchor: damage-scored, self-gated. */
    protected BlockPos findAnchorSpot(MinecraftClient client, ClientPlayerEntity self, LivingEntity target) {
        ClientWorld world = client.world;
        // Anchor detonation is a single instant - score against where the target will
        // BE by the time the place/charge/detonate chain completes (2 ticks out).
        Vec3d predicted = dev.zephyr.core.crystal.CrystalSolver.predictPos(target, 2);
        BlockPos center = BlockPos.ofFloored(predicted);
        int r = (int) Math.ceil(range.get());
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPos pos : BlockPos.iterateOutwards(center, r, r, r)) {
            if (!RaycastValidator.canPlaceAnchor(world, pos.down(), self)) {
                continue;
            }
            if (!world.getBlockState(pos).isAir()) {
                continue;
            }
            Vec3d blast = Vec3d.ofCenter(pos);
            double targetDmg = dev.zephyr.core.combat.DamageCalculator.explosionDamage(world, blast,
                    dev.zephyr.core.combat.DamageCalculator.ANCHOR_STRENGTH, predicted, target);
            double selfDmg = DamageCalculator.anchorDamage(world, blast, self);
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

    /** SafeAnchor hook: place a protective block between self and anchor. @return success. */
    protected boolean placeProtection(MinecraftClient client, ClientPlayerEntity self, BlockPos anchor) {
        return true;
    }

    protected BlockHitResult anchorFaceHit(BlockPos anchorPos) {
        Vec3d hitVec = Vec3d.ofCenter(anchorPos).add(0, 0.45, 0);
        return new BlockHitResult(hitVec, Direction.UP, anchorPos, false);
    }

    protected void aimAt(ClientPlayerEntity self, BlockPos pos) {
        Vec3d point = Vec3d.ofCenter(pos.up());
        RotationManager.request(RotationRequest
                .to(MathUtil.yawTo(self.getEyePos(), point), MathUtil.pitchTo(self.getEyePos(), point))
                .speed(rotMin.get(), rotMax.get())
                .jitter(0.4)
                .silent(true)
                .validWhile(() -> stage != Stage.IDLE)
                .expiresAfter(6)
                .build());
    }

    @Override
    public String hudLabel() {
        return name + " · " + stage.name().toLowerCase();
    }
}
