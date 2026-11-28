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
     * Where the target's feet will be {@code ticks} from now: vanilla gravity and drag
     * while airborne, never below the floor under them.
     */
    public static Vec3d predictFeet(ClientWorld world, Entity target, int ticks) {
        double x = target.getX(), y = target.getY(), z = target.getZ();
        double vx = x - target.lastX, vy = y - target.lastY, vz = z - target.lastZ;
        double floor = floorUnder(world, target);
        boolean airborne = y - floor > 0.05;
        for (int i = 0; i < ticks; i++) {
            x += vx;
            z += vz;
            if (airborne) {
                y = Math.max(floor, y + vy);
                vy = (vy - 0.08) * 0.98;
                vx *= 0.91;
                vz *= 0.91;
            }
        }
        return new Vec3d(x, y, z);
    }

    /** Top of the first solid block under the entity (searched 12 blocks down). */
    public static double floorUnder(ClientWorld world, Entity target) {
        BlockPos at = target.getBlockPos();
        for (int i = 0; i <= 12; i++) {
            BlockPos below = at.down(i + 1);
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                return below.getY() + 1;
            }
        }
        return target.getY() - 12;
    }

    /** How much a point of damage to us counts against a point of damage to them. */
    public static double selfWeight = 0.8;

    /** Extra weight on our own damage for each block we stand above the target (up to two). */
    public static double heightPenalty = 1.0;

    /** What a crystal on one particular base is worth, and the spot itself. */
    public record Rated(Spot spot, double value) {
    }

    /**
     * Rates one base block: damage to them where they will be, minus most of the damage to
     * us, minus the time a missing block costs. Null when it cannot be used or breaks a limit.
     */
    public static Rated rate(ClientWorld world, Entity self, LivingEntity target, BlockPos base,
                             double placeRange, double minTargetDamage, double maxSelfDamage,
                             java.util.function.Predicate<BlockPos> breakable) {
        Search search = new Search(world, self, target, placeRange, minTargetDamage, maxSelfDamage, breakable);
        Option option = search.screen(base);
        return option == null ? null : search.rate(option, Double.NEGATIVE_INFINITY);
    }

    /** A block that passed the cheap checks, and the most it could possibly be worth. */
    private record Option(BlockPos base, boolean existing, boolean occupied, Vec3d feet, double limit) {
    }

    /**
     * One search. What every block shares (where the target will be, which cells hold a
     * crystal) is worked out once; each block is then screened without raycasts, and only
     * those whose best case still beats the current pick are calculated in full.
     */
    private static final class Search {
        private final ClientWorld world;
        private final Entity self;
        private final LivingEntity target;
        private final double placeRange;
        private final double minTargetDamage;
        private final double maxSelfDamage;
        private final java.util.function.Predicate<BlockPos> breakable;
        private final Vec3d eye;
        private final Vec3d selfFeet;
        private final double weight;
        private final Vec3d[] future = new Vec3d[6];
        private final java.util.Set<BlockPos> crystals = new java.util.HashSet<>();

        Search(ClientWorld world, Entity self, LivingEntity target, double placeRange,
               double minTargetDamage, double maxSelfDamage, java.util.function.Predicate<BlockPos> breakable) {
            this.world = world;
            this.self = self;
            this.target = target;
            this.placeRange = placeRange;
            this.minTargetDamage = minTargetDamage;
            this.maxSelfDamage = maxSelfDamage;
            this.breakable = breakable;
            this.eye = self.getEyePos();
            this.selfFeet = new Vec3d(self.getX(), self.getY(), self.getZ());
            this.weight = selfWeight * (1.0 + heightPenalty
                    * Math.max(0.0, Math.min(2.0, self.getY() - target.getY())));
            Box around = self.getBoundingBox().expand(placeRange + 2.0);
            for (EndCrystalEntity crystal : world.getEntitiesByClass(EndCrystalEntity.class, around, Entity::isAlive)) {
                crystals.add(crystal.getBlockPos());
            }
        }

        Option screen(BlockPos base) {
            BlockPos cell = base.up();
            if (!breakable.test(cell) || eye.distanceTo(Vec3d.ofCenter(base).add(0, 0.5, 0)) > placeRange) {
                return null;
            }
            BlockState state = world.getBlockState(base);
            boolean existing = state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
            if (!existing && !state.isAir()) {
                return null;
            }
            boolean occupied = crystals.contains(cell);
            // Ticks until it goes off: break an existing crystal now, place and break, or build first.
            int ticks = occupied ? 1 : existing ? 3 : 5;
            if (future[ticks] == null) {
                future[ticks] = predictFeet(world, target, ticks);
            }
            double limit = DamageCalculator.crystalDamageLimit(world, Vec3d.ofBottomCenter(cell), target,
                    future[ticks], !existing);
            if (limit < minTargetDamage) {
                return null;
            }
            return new Option(base.toImmutable(), existing, occupied, future[ticks],
                    limit - (existing ? 0.0 : 0.75));
        }

        /** Full calculation; null when unusable or when it cannot be worth more than {@code beat}. */
        Rated rate(Option option, double beat) {
            BlockPos base = option.base();
            BlockPos cell = base.up();
            double cost = option.existing() ? 0.0 : 0.75;
            Vec3d blast = Vec3d.ofBottomCenter(cell);
            // Their damage first: it alone often shows this block cannot win.
            double theirs = DamageCalculator.crystalDamageAt(world, blast, target, option.feet(), !option.existing());
            if (theirs < minTargetDamage || theirs - cost <= beat) {
                return null;
            }
            double ours = DamageCalculator.crystalDamageAt(world, blast, self, selfFeet, !option.existing());
            double value = theirs - weight * ours - cost;
            if (ours > maxSelfDamage || value <= beat) {
                return null;
            }
            if (!option.occupied() && (!crystalCellLegal(world, cell, self) || self.getBoundingBox().intersects(
                    new Box(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + 2, cell.getZ() + 1)))) {
                return null;
            }
            BlockHitResult hit = null;
            if (!option.existing()) {
                if (!blockCellPlaceable(world, base, self)) {
                    return null;
                }
                hit = blockPlacementHit(world, base, eye);
                if (hit == null) {
                    return null;
                }
            }
            return new Rated(new Spot(cell, base, option.existing() ? null : base, theirs, ours, hit), value);
        }

        /** The best of the screened blocks: most promising first, stop when none can win. */
        Rated best(List<Option> options) {
            options.sort((a, b) -> Double.compare(b.limit(), a.limit()));
            Rated best = null;
            for (Option option : options) {
                if (best != null && option.limit() <= best.value()) {
                    break;
                }
                Rated rated = rate(option, best == null ? Double.NEGATIVE_INFINITY : best.value());
                if (rated != null) {
                    best = rated;
                }
            }
            return best;
        }
    }

    /**
     * Searches every block in reach for the best base, existing or to be built.
     * {@code newOnly}: only cells that still need obsidian (the block owed after a hit).
     */
    public static Rated findOptimalSpot(ClientWorld world, Entity self, LivingEntity target,
                                        double placeRange, double minTargetDamage, double maxSelfDamage,
                                        boolean allowBuild, boolean newOnly,
                                        java.util.function.Predicate<BlockPos> breakable) {
        Search search = new Search(world, self, target, placeRange, minTargetDamage, maxSelfDamage, breakable);
        // Ledge: they stand higher than us. A crystal level with their feet hits them in
        // full while the ledge shields us. Its own obsidian first; else build beside it.
        if (onLedge(world, self, target)) {
            Rated ledge = searchLedge(search, true);
            if (ledge == null && allowBuild) {
                ledge = searchLedge(search, false);
            }
            if (ledge != null) {
                return ledge;
            }
        }
        BlockPos me = self.getBlockPos();
        int r = (int) Math.ceil(placeRange);
        // From under our feet up to the target's: a high target is reached by building on blocks.
        int top = Math.max(me.getY() + 2, target.getBlockY());
        List<Option> options = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(me.getX() - r, me.getY() - 3, me.getZ() - r,
                me.getX() + r, top, me.getZ() + r)) {
            BlockState state = world.getBlockState(p);
            boolean existing = state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
            if (existing ? newOnly : !(allowBuild && state.isAir())) {
                continue;
            }
            Option option = search.screen(p);
            if (option != null && (existing || hasNeighbour(world, p))) {
                options.add(option);
            }
        }
        return search.best(options);
    }

    /** True while the target is standing on something about a block or more above our feet. */
    public static boolean onLedge(ClientWorld world, Entity self, Entity target) {
        return target.isOnGround() && floorUnder(world, target) - self.getY() >= 0.9;
    }

    /** Best base in the ledge layer (top level with the target's feet) within two of them. */
    private static Rated searchLedge(Search search, boolean existingBlocks) {
        Entity target = search.target;
        BlockPos under = BlockPos.ofFloored(target.getX(), target.getY() - 0.5, target.getZ());
        List<Option> options = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(under.add(-2, 0, -2), under.add(2, 0, 2))) {
            BlockState state = search.world.getBlockState(p);
            boolean existing = state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
            // A new block has to go against the side of the ledge.
            if (existingBlocks ? !existing : !(state.isAir() && besideSolid(search.world, p))) {
                continue;
            }
            Option option = search.screen(p);
            if (option != null) {
                options.add(option);
            }
        }
        return search.best(options);
    }

    private static boolean besideSolid(ClientWorld world, BlockPos p) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState n = world.getBlockState(p.offset(d));
            if (!n.isAir() && !n.isReplaceable()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNeighbour(ClientWorld world, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState n = world.getBlockState(p.offset(d));
            if (!n.isAir() && !n.isReplaceable()) {
                return true;
            }
        }
        return false;
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
            BlockState cellState = world.getBlockState(cell);
            if (!cellState.isAir() && !cellState.isReplaceable()) {
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
