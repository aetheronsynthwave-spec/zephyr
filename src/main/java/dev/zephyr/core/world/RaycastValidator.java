package dev.zephyr.core.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * World-space validation for every combat action: line-of-sight to points and hitboxes,
 * crystal placement legality, and anchor support checks. All probes use vanilla raycasts
 * against the collision shape context, so results match what the server will judge.
 */
public final class RaycastValidator {

    private RaycastValidator() {
    }

    private static final Vec3d[] CORNER_OFFSETS = buildCornerOffsets();

    private static Vec3d[] buildCornerOffsets() {
        double[] xs = {0.1, 0.5, 0.9};
        double[] ys = {0.1, 0.5, 0.9};
        double[] zs = {0.1, 0.5, 0.9};
        Vec3d[] out = new Vec3d[27];
        int i = 0;
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    out[i++] = new Vec3d(x, y, z);
                }
            }
        }
        return out;
    }

    public static boolean canSee(Vec3d from, Vec3d to, Entity context) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return false;
        }
        BlockHitResult result = world.raycast(new RaycastContext(
                from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, context));
        return result.getType() == HitResult.Type.MISS;
    }

    /**
     * True when at least one sampled point of {@code box} is visible from {@code from}
     * (sampling the 3x3x3 corner/center grid of the box - the same points a server-side
     * crystal/attack raycast can realistically clear).
     */
    public static boolean hitboxVisible(Vec3d from, Box box, Entity context) {
        for (Vec3d offset : CORNER_OFFSETS) {
            Vec3d point = new Vec3d(
                    box.minX + (box.maxX - box.minX) * offset.x,
                    box.minY + (box.maxY - box.minY) * offset.y,
                    box.minZ + (box.maxZ - box.minZ) * offset.z);
            if (canSee(from, point, context)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Full crystal placement legality for the block position {@code base} (the crystal
     * would occupy {@code base.up()}):
     * support block is obsidian/bedrock, crystal cell is air, no entity intersects the
     * crystal box, and the placement spot is reachable per {@code wallsRange}.
     */
    public static boolean canPlaceCrystal(ClientWorld world, BlockPos base, Entity self,
                                          boolean checkSupport, boolean requireVisible) {
        if (!world.isPosLoaded(base)) {
            return false;
        }
        BlockState support = world.getBlockState(base);
        if (checkSupport && !support.isOf(Blocks.OBSIDIAN) && !support.isOf(Blocks.BEDROCK)) {
            return false;
        }
        BlockPos crystalPos = base.up();
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
            if (e != self && !(e instanceof EndCrystalEntity)) {
                return false;
            }
        }
        if (requireVisible) {
            Vec3d hitPoint = Vec3d.ofCenter(crystalPos);
            if (!canSee(self.getEyePos(), hitPoint, self)) {
                return false;
            }
        }
        return true;
    }

    /** Block face for placing onto {@code base}: prefer UP face (standard crystal/anchor placement). */
    public static BlockHitResult placementHit(BlockPos base) {
        Vec3d hitVec = Vec3d.ofBottomCenter(base).add(0, 1.0, 0);
        return new BlockHitResult(hitVec, Direction.UP, base, false);
    }

    /** Anchor needs exactly one air cell above solid ground; glowstone interaction requires reach. */
    public static boolean canPlaceAnchor(ClientWorld world, BlockPos base, Entity self) {
        BlockState support = world.getBlockState(base);
        if (!support.isSideSolidFullSquare(world, base, Direction.UP)) {
            return false;
        }
        BlockPos anchorPos = base.up();
        if (!world.getBlockState(anchorPos).isAir()) {
            return false;
        }
        return canSee(self.getEyePos(), Vec3d.ofCenter(anchorPos), self);
    }
}
