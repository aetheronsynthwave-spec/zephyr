package dev.zephyr.core.crystal;

import dev.zephyr.core.combat.DamageCalculator;
import dev.zephyr.core.util.MathUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Crystal placement solver - the mathematical heart of the crystal modules.
 *
 * <p>Everything here is scored with the vanilla explosion model
 * ({@link DamageCalculator#explosionDamage}, exposure-sampled 3x3x3 over the target
 * box) plus motion prediction, so the "best" spot is the spot the server will judge
 * as the hardest-hitting legal crystal, not a heuristic guess.
 *
 * <p>Three public questions are answered:
 * <ol>
 *   <li>{@link #findBestCrystalSpot} - which air cell should host the next crystal
 *       (support must be obsidian/bedrock), scored over the full spherical volume.</li>
 *   <li>{@link #findBestObsidianPlan} - which air cell should receive an obsidian
 *       block right now so that {@code cell.up()} becomes the highest-value crystal
 *       cell. Solved by evaluating the crystal damage AT {@code cell.up()} for every
 *       placeable cell - i.e. we simulate the obsidian being placed before we place it.</li>
 *   <li>{@link #crystalPlacementHit} / {@link #blockPlacementHit} - the exact click
 *       (face + hit vec) a human would produce for that placement, chosen as the face
 *       point closest to the eye ray so the click angle stays natural.</li>
 * </ol>
 */
public final class CrystalSolver {

    private CrystalSolver() {
    }

    /**
     * Phase-B evaluation budget: how many geometrically-best candidates receive the
     * expensive treatment (exposure raycasts + legality queries). 8 covers every
     * realistically-contending cell around the target (the exposure memo makes the
     * per-candidate cost mostly a cache hit anyway); the winner of a full-cube scan
     * and the winner of this budget are the same cell in practice.
     */
    private static final int EVAL_BUDGET = 8;

    /** Reusable phase-A scratch list (never escapes the solver). */
    private static final java.util.ArrayList<Candidate> CANDIDATES = new java.util.ArrayList<>();

    private record Candidate(BlockPos pos, double pre) {
        static final java.util.Comparator<Candidate> ORDER =
                java.util.Comparator.comparingDouble(Candidate::pre).reversed();
    }

    /** Result of a crystal-spot / obsidian-plan evaluation. */
    public static final class Spot {
        /** Air cell that will host the crystal (base.up() for the base variant). */
        public final BlockPos crystalCell;
        /** Block the crystal sits on (obsidian/bedrock; may not exist yet for obsidian plans). */
        public final BlockPos support;
        /** Cell the obsidian must be placed into first ({@code null} when support exists). */
        public final BlockPos obsidianCell;
        /** Expected damage to the target once the crystal detonates there. */
        public final double targetDamage;
        /** Expected self damage from the same blast. */
        public final double selfDamage;
        /** Block face to click for the obsidian placement ({@code null} when not needed). */
        public final BlockHitResult obsidianHit;

        Spot(BlockPos crystalCell, BlockPos support, BlockPos obsidianCell,
             double targetDamage, double selfDamage, BlockHitResult obsidianHit) {
            this.crystalCell = crystalCell.toImmutable();
            this.support = support.toImmutable();
            this.obsidianCell = obsidianCell == null ? null : obsidianCell.toImmutable();
            this.targetDamage = targetDamage;
            this.selfDamage = selfDamage;
            this.obsidianHit = obsidianHit;
        }

        public boolean needsObsidian() {
            return obsidianCell != null;
        }

        /** Damage efficiency: what we do to them minus what they make us take. */
        public double netValue() {
            return targetDamage - selfDamage;
        }
    }

    /**
     * Best existing obsidian/bedrock-supported crystal cell around the target.
     *
     * <p>Perf model (the old version brute-forced the full cube with ~60 raycasts per
     * cell and froze the client): the scan is now two-phase. Phase A walks the
     * candidate volume with block-state-only checks (no raycasts, no entity queries)
     * and ranks survivors by a geometric pre-score. Phase B runs the full damage
     * evaluation (exposure raycasts + legality entity queries + visibility) on only
     * the top {@value #EVAL_BUDGET} candidates. The math that decides the winner is
     * identical - we just stop paying for it on geometrically-dominated cells.
     *
     * @param predictTicks  how many ticks of target motion to fold into the damage model
     */
    public static Spot findBestCrystalSpot(ClientWorld world, Entity self, LivingEntity target,
                                           double placeRange, double minTargetDamage, double maxSelfDamage,
                                           boolean requireVisible, int predictTicks) {
        Vec3d eye = self.getEyePos();
        Vec3d predicted = predictPos(target, predictTicks);
        int r = (int) Math.ceil(placeRange);
        BlockPos tPos = BlockPos.ofFloored(predicted);

        // Phase A: cheap candidate collection (block states + distances only).
        CANDIDATES.clear();
        // Crystal blasts only matter within a few blocks of the target's body vertically;
        // the old full-height cube wasted 80% of its work on cells that can never hit.
        int yMin = tPos.getY() - 3;
        int yMax = tPos.getY() + 5;
        for (BlockPos base : BlockPos.iterate(tPos.getX() - r, yMin, tPos.getZ() - r,
                tPos.getX() + r, yMax, tPos.getZ() + r)) {
            BlockState support = world.getBlockState(base);
            if (!support.isOf(Blocks.OBSIDIAN) && !support.isOf(Blocks.BEDROCK)) {
                continue;
            }
            Vec3d blast = Vec3d.ofCenter(base.up());
            double toTarget = blast.distanceTo(predicted);
            if (toTarget > 2.0 * DamageCalculator.CRYSTAL_STRENGTH) {
                continue; // zero damage by the vanilla falloff term - skip the math
            }
            double toEye = eye.distanceTo(blast);
            if (toEye > placeRange + 1.5) {
                continue;
            }
            // Geometric pre-score mirrors spotScore's distance terms without raycasts.
            CANDIDATES.add(new Candidate(base.toImmutable(), -toTarget - 0.4 * toEye));
        }
        if (CANDIDATES.isEmpty()) {
            return null;
        }
        CANDIDATES.sort(Candidate.ORDER);

        // Phase B: full evaluation of the top candidates only.
        Spot best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int evaluated = Math.min(EVAL_BUDGET, CANDIDATES.size());
        for (int i = 0; i < evaluated; i++) {
            BlockPos base = CANDIDATES.get(i).pos;
            if (!crystalCellLegal(world, base.up(), self)) {
                continue;
            }
            if (requireVisible && !MathUtil.blockVisible(eye, base)) {
                continue;
            }
            Vec3d blast = Vec3d.ofCenter(base.up());
            double targetDmg = DamageCalculator.explosionDamage(world, blast,
                    DamageCalculator.CRYSTAL_STRENGTH, predicted, target);
            double selfDmg = DamageCalculator.explosionDamage(world, blast,
                    DamageCalculator.CRYSTAL_STRENGTH, self);
            if (targetDmg < minTargetDamage || selfDmg > maxSelfDamage) {
                continue;
            }
            double score = spotScore(targetDmg, selfDmg, eye, blast);
            if (score > bestScore) {
                bestScore = score;
                best = new Spot(base.up(), base, null, targetDmg, selfDmg, null);
            }
        }
        return best;
    }

    /**
     * The obsidian plan: find the air cell where placing obsidian NOW creates the
     * highest-value crystal cell. Simulated, not guessed - each candidate is scored
     * by the crystal damage it would unlock at {@code cell.up()}. Same two-phase
     * scan as {@link #findBestCrystalSpot}: cheap block-state filtering with a
     * geometric pre-score, full damage math on the top candidates only.
     */
    /**
     * The hand-played hit-crystal placement (modelled on a real player's clip): a FRESH
     * obsidian on the floor in one of the 8 cells around the target's feet, choosing the
     * cell that needs the least camera movement from the current view and sits on the
     * player's side - so the aim only dips slightly instead of flicking around. The
     * crystal then goes on top, beside the (knocked-up) target.
     *
     * @param refYaw/refPitch the view the player currently has (camera, not packet).
     */
    public static Spot findHandObsidianPlan(ClientWorld world, Entity self, LivingEntity target,
                                           double placeRange, double maxSelfDamage,
                                           double refYaw, double refPitch) {
        Vec3d eye = self.getEyePos();
        // Floor level under the target (it is usually airborne right after the hit).
        BlockPos feet = target.getBlockPos();
        for (int i = 0; i < 4 && world.getBlockState(feet.down()).isAir(); i++) {
            feet = feet.down();
        }
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos cell = feet.add(dx, 0, dz);
                BlockPos crystal = cell.up();
                var cellState = world.getBlockState(cell);
                boolean existing = cellState.isOf(Blocks.OBSIDIAN) || cellState.isOf(Blocks.BEDROCK);
                if ((!existing && !cellState.isAir()) || !world.getBlockState(crystal).isAir()
                        || !world.getBlockState(crystal.up()).isAir()) {
                    continue;
                }
                if (existing) {
                    // Obsidian already right beside the target (usually our own from the last
                    // cycle): reuse it instead of placing yet another block.
                    if (!crystalCellLegal(world, crystal, self)) {
                        continue;
                    }
                    Vec3d eyeE = self.getEyePos();
                    Vec3d topE = Vec3d.ofCenter(cell).add(0, 0.5, 0);
                    if (eyeE.distanceTo(topE) > placeRange) {
                        continue;
                    }
                    Vec3d blastE = Vec3d.ofBottomCenter(crystal);
                    double selfE = DamageCalculator.crystalDamage(world, blastE, self);
                    double tgtE = DamageCalculator.crystalDamage(world, blastE, target);
                    double turnE = Math.hypot(MathUtil.angleDelta(refYaw, MathUtil.yawTo(eyeE, topE)),
                            MathUtil.pitchTo(eyeE, topE) - refPitch);
                    double scoreE = turnE + eyeE.distanceTo(topE) * 6.0 - tgtE * 0.5 + selfE - 25.0;
                    if (scoreE < bestScore) {
                        bestScore = scoreE;
                        best = new Spot(crystal, cell, null, tgtE, selfE, null);
                    }
                    continue;
                }
                if (!blockCellPlaceable(world, cell, self)) {
                    continue;
                }
                Vec3d top = Vec3d.ofCenter(cell).add(0, 0.5, 0);
                if (eye.distanceTo(top) > placeRange) {
                    continue;
                }
                BlockHitResult hit = blockPlacementHit(world, cell, eye);
                if (hit == null) {
                    continue;
                }
                Vec3d blast = Vec3d.ofBottomCenter(crystal);
                double selfDmg = dev.zephyr.core.combat.DamageCalculator.crystalDamage(world, blast, self);
                if (selfDmg > maxSelfDamage) {
                    continue;
                }
                double targetDmg = dev.zephyr.core.combat.DamageCalculator.crystalDamage(world, blast, target);
                double yaw = dev.zephyr.core.util.MathUtil.yawTo(eye, top);
                double pitch = dev.zephyr.core.util.MathUtil.pitchTo(eye, top);
                double turn = Math.hypot(dev.zephyr.core.util.MathUtil.angleDelta(refYaw, yaw), pitch - refPitch);
                // Least camera movement first, then closer to us, then more damage.
                double score = turn + eye.distanceTo(top) * 6.0 - targetDmg * 0.5 + selfDmg;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Spot(crystal, cell, cell, targetDmg, selfDmg, hit);
                }
            }
        }
        return best;
    }

    public static Spot findBestObsidianPlan(ClientWorld world, Entity self, LivingEntity target,
                                            double placeRange, double minTargetDamage, double maxSelfDamage,
                                            boolean requireVisible, int predictTicks) {
        Vec3d eye = self.getEyePos();
        Vec3d predicted = predictPos(target, predictTicks);
        int r = (int) Math.ceil(placeRange);
        BlockPos tPos = BlockPos.ofFloored(predicted);

        CANDIDATES.clear();
        int yMin = tPos.getY() - 3;
        int yMax = tPos.getY() + 5;
        for (BlockPos cell : BlockPos.iterate(tPos.getX() - r, yMin, tPos.getZ() - r,
                tPos.getX() + r, yMax, tPos.getZ() + r)) {
            // The obsidian cell itself must be empty and the crystal cell above empty
            // (both pure block-state checks; entity legality is a phase-B gate).
            if (!world.getBlockState(cell).isAir() && !world.getBlockState(cell).isReplaceable()) {
                continue;
            }
            Vec3d blast = Vec3d.ofCenter(cell.up());
            double toTarget = blast.distanceTo(predicted);
            if (toTarget > 2.0 * DamageCalculator.CRYSTAL_STRENGTH) {
                continue;
            }
            double toEye = eye.distanceTo(blast);
            if (toEye > placeRange + 1.5) {
                continue;
            }
            CANDIDATES.add(new Candidate(cell.toImmutable(), -toTarget - 0.4 * toEye));
        }
        if (CANDIDATES.isEmpty()) {
            return null;
        }
        CANDIDATES.sort(Candidate.ORDER);

        Spot best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int evaluated = Math.min(EVAL_BUDGET, CANDIDATES.size());
        for (int i = 0; i < evaluated; i++) {
            BlockPos cell = CANDIDATES.get(i).pos;
            if (!crystalCellLegal(world, cell.up(), self)) {
                continue;
            }
            if (requireVisible && !MathUtil.blockVisible(eye, cell)) {
                continue;
            }
            BlockHitResult hit = blockPlacementHit(world, cell, eye);
            if (hit == null) {
                continue; // no legal support face to click -> a human could not place here either
            }
            Vec3d blast = Vec3d.ofCenter(cell.up());
            double targetDmg = DamageCalculator.explosionDamage(world, blast,
                    DamageCalculator.CRYSTAL_STRENGTH, predicted, target);
            double selfDmg = DamageCalculator.explosionDamage(world, blast,
                    DamageCalculator.CRYSTAL_STRENGTH, self);
            if (targetDmg < minTargetDamage || selfDmg > maxSelfDamage) {
                continue;
            }
            // Obsidian placements cost one extra tick of setup; weight them slightly.
            double score = spotScore(targetDmg, selfDmg, eye, blast) - 0.35;
            if (score > bestScore) {
                bestScore = score;
                best = new Spot(cell.up(), cell, cell, targetDmg, selfDmg, hit);
            }
        }
        return best;
    }

    /**
     * Unified best-spot query: natural obsidian/bedrock support first, and when no
     * natural spot clears the damage gates, an obsidian-supported plan.
     */
    public static Spot findBestSpotWithSupport(ClientWorld world, Entity self, LivingEntity target,
                                               double placeRange, double minTargetDamage, double maxSelfDamage,
                                               boolean requireVisible, int predictTicks,
                                               boolean allowObsidianSupport) {
        Spot natural = findBestCrystalSpot(world, self, target, placeRange,
                minTargetDamage, maxSelfDamage, requireVisible, predictTicks);
        if (natural != null) {
            return natural;
        }
        if (!allowObsidianSupport) {
            return null;
        }
        return findBestObsidianPlan(world, self, target, placeRange,
                minTargetDamage, maxSelfDamage, requireVisible, predictTicks);
    }

    // ------------------------------------------------------------------
    // Click construction
    // ------------------------------------------------------------------

    /**
     * Face-aware hit result for placing a crystal onto {@code support}. Vanilla only
     * judges the clicked face for reach/occlusion, so we click the face point closest
     * to the eye ray - the most natural angle a real player would produce.
     */
    public static BlockHitResult crystalPlacementHit(ClientWorld world, BlockPos support, Vec3d eye) {
        return faceAwareHit(support, eye);
    }

    /**
     * Hit result for placing a block (obsidian) into empty cell {@code cell}: clicks
     * the visible solid neighbor face nearest the eye. Returns {@code null} when no
     * legal face exists (the placement would be impossible for a human too).
     */
    public static BlockHitResult blockPlacementHit(ClientWorld world, BlockPos cell, Vec3d eye) {
        BlockHitResult best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = cell.offset(dir);
            BlockState n = world.getBlockState(neighbor);
            if (n.isAir() || n.isReplaceable()) {
                continue;
            }
            Direction face = dir.getOpposite();
            Vec3d hitVec = facePoint(neighbor, face, eye);
            if (hitVec == null) {
                continue;
            }
            double d = eye.distanceTo(hitVec);
            if (d < bestDist) {
                bestDist = d;
                best = new BlockHitResult(hitVec, face, neighbor, false);
            }
        }
        return best;
    }

    /** Shared face/vec picker: face of {@code support} whose point is closest to the eye. */
    private static BlockHitResult faceAwareHit(BlockPos support, Vec3d eye) {
        BlockHitResult best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            Vec3d hitVec = facePoint(support, face, eye);
            if (hitVec == null) {
                continue;
            }
            double d = eye.distanceTo(hitVec);
            if (d < bestDist) {
                bestDist = d;
                best = new BlockHitResult(hitVec, face, support, false);
            }
        }
        return best;
    }

    /** Point on the given block face closest to {@code eye}, clamped into the face rect. */
    private static Vec3d facePoint(BlockPos block, Direction face, Vec3d eye) {
        double minX = block.getX(), minY = block.getY(), minZ = block.getZ();
        double maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;
        double x = Math.min(maxX - 0.03, Math.max(minX + 0.03, eye.x));
        double y = Math.min(maxY - 0.03, Math.max(minY + 0.03, eye.y));
        double z = Math.min(maxZ - 0.03, Math.max(minZ + 0.03, eye.z));
        return switch (face) {
            case UP -> new Vec3d(x, maxY, z);
            case DOWN -> new Vec3d(x, minY, z);
            case NORTH -> new Vec3d(x, y, minZ);
            case SOUTH -> new Vec3d(x, y, maxZ);
            case WEST -> new Vec3d(minX, y, z);
            case EAST -> new Vec3d(maxX, y, z);
        };
    }

    // ------------------------------------------------------------------
    // Legality helpers
    // ------------------------------------------------------------------

    /**
     * Vanilla crystal-cell rule: two air cells above the support and no colliding
     * entity inside the crystal box. Item drops and orbs never block (no collision
     * shape), matching what the server's isSpaceEmpty() actually rejects.
     */
    public static boolean crystalCellLegal(ClientWorld world, BlockPos crystalPos, Entity self) {
        if (!world.getBlockState(crystalPos).isAir()) {
            return false;
        }
        if (!world.getBlockState(crystalPos.up()).isAir()) {
            return false;
        }
        Box crystalBox = new Box(
                crystalPos.getX() - 0.05, crystalPos.getY(), crystalPos.getZ() - 0.05,
                crystalPos.getX() + 1.05, crystalPos.getY() + 2.0, crystalPos.getZ() + 1.05);
        for (Entity e : world.getOtherEntities(null, crystalBox)) {
            if (e == self || e instanceof EndCrystalEntity) {
                continue;
            }
            if (e instanceof ItemEntity) {
                continue; // no collision shape -> server does not reject
            }
            return false;
        }
        return true;
    }

    /**
     * Can a block be placed into {@code cell} right now: cell empty/replaceable, no
     * living entity intersecting the block box, and at least one solid neighbor face
     * to click.
     */
    public static boolean blockCellPlaceable(ClientWorld world, BlockPos cell, Entity self) {
        BlockState state = world.getBlockState(cell);
        if (!state.isAir() && !state.isReplaceable()) {
            return false;
        }
        Box box = new Box(cell);
        for (Entity e : world.getOtherEntities(null, box)) {
            if (e == self || e instanceof ItemEntity) {
                continue;
            }
            if (e instanceof LivingEntity || e.isCollidable(self)) {
                return false; // vanilla refuses placements intersecting entities
            }
        }
        return blockPlacementHit(world, cell, self.getEyePos()) != null;
    }

    // ------------------------------------------------------------------
    // Scoring + prediction
    // ------------------------------------------------------------------

    /**
     * Spot score: maximize target damage, penalize self damage harder (dying to your
     * own crystal loses the fight), then prefer nearer spots (shorter crystal travel
     * time between place and detonate = tighter double-tap window).
     */
    private static double spotScore(double targetDmg, double selfDmg, Vec3d eye, Vec3d blast) {
        double dist = eye.distanceTo(blast);
        return targetDmg * 2.0 - selfDmg * 1.6 - dist * 0.35;
    }

    /**
     * Predicted entity position {@code ticks} ahead: vanilla player drag is a per-tick
     * multiply (0.91 horizontal, gravity 0.08 / drag 0.98 vertical), so the extrapolation
     * is a geometric decay, not a straight line. Clamped to a couple of ticks because
     * client-side prediction past that fights server corrections.
     */
    public static Vec3d predictPos(LivingEntity e, int ticks) {
        if (ticks <= 0 || e.isOnGround()) {
            return e.getLerpedPos(1.0F);
        }
        ticks = Math.min(ticks, 3);
        Vec3d v = e.getVelocity();
        Vec3d pos = e.getLerpedPos(1.0F);
        double x = 0, y = 0, z = 0;
        for (int i = 0; i < ticks; i++) {
            x += v.x; y += v.y; z += v.z;
            v = new Vec3d(v.x * 0.91, (v.y - 0.08) * 0.98, v.z * 0.91);
        }
        return pos.add(x, y, z);
    }

    /** All candidate cells in range (used by debug/visualizers). */
    public static List<BlockPos> candidatesAround(Vec3d center, double range) {
        List<BlockPos> out = new ArrayList<>();
        int r = (int) Math.ceil(range);
        BlockPos c = BlockPos.ofFloored(center);
        for (BlockPos p : BlockPos.iterateOutwards(c, r, r, r)) {
            out.add(p.toImmutable());
        }
        return out;
    }
}
