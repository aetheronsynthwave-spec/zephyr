package dev.zephyr.modules.crystal;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Blocks;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;

/**
 * SafeAnchor - AnchorMacro with a 9-13 ms randomized pacing profile and a protective
 * cover stage: before detonating, a protective block is placed on the segment between
 * the local player's eyes and the anchor so the blast exposure drops below the
 * self-damage ceiling. The module refuses to detonate unshielded - it waits (paced)
 * for cover to be placeable instead of eating avoidable damage.
 */
public final class SafeAnchor extends AnchorMacro {

    public SafeAnchor() {
        super("SafeAnchor");
        // Safe pacing profile: 9-13 ms between stages (config may override).
        paceMin.set(9.0);
        paceMax.set(13.0);
    }

    @Override
    protected boolean protectivePlacement() {
        return true;
    }

    @Override
    protected boolean placeProtection(MinecraftClient client, ClientPlayerEntity self, BlockPos anchor) {
        if (client.world == null) {
            return false;
        }
        if (anchor.equals(coveredFor)) {
            return finishCover(client, self); // one shield per anchor, not a wall
        }
        BlockPos cover = findCoverPosition(client, self, anchor);
        if (cover == null) {
            // No legal cover: hard-fail this detonation cycle rather than self-destruct.
            stage = Stage.IDLE;
            stageTimer.invalidate();
            anchorPos = null;
            return true;
        }
        if (!client.world.getBlockState(cover).isAir()) {
            return finishCover(client, self); // cover already standing
        }
        // Glowstone is the classic shield block (you carry it anyway); obsidian as fallback.
        int coverSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isGlowstone);
        if (coverSlot == -1) {
            coverSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isObsidian);
        }
        BlockHitResult hit = supportHit(client, cover);
        if (coverSlot == -1 || hit == null) {
            stage = Stage.IDLE;
            stageTimer.invalidate();
            anchorPos = null;
            return false;
        }
        InventoryManager.selectHotbar(client, coverSlot);
        // Click an existing neighbour's face (the old code clicked the empty cell itself,
        // which the server rejects - so the cover never appeared).
        dev.zephyr.core.combat.CombatActions.interactBlock(client, hit);
        if (client.world.getBlockState(cover).isAir()) {
            return false; // try again on the next paced attempt
        }
        coveredFor = anchor.toImmutable();
        return finishCover(client, self);
    }

    private BlockPos coveredFor;

    /** After cover: hold a non-glowstone item so the next anchor click detonates. */
    private boolean finishCover(MinecraftClient client, ClientPlayerEntity self) {
        if (ItemUtil.isGlowstone(self.getMainHandStack())) {
            // Detonate with the sword like the hand routine; any non-glowstone item as fallback.
            int slot = InventoryManager.findHotbarSlot(self,
                    s -> ItemUtil.id(s).endsWith("_sword") || ItemUtil.id(s).endsWith("_axe"));
            if (slot == -1) {
                slot = InventoryManager.findHotbarSlot(self, s -> !ItemUtil.isGlowstone(s));
            }
            if (slot != -1) {
                InventoryManager.selectHotbar(client, slot);
            }
        }
        return !ItemUtil.isGlowstone(self.getMainHandStack());
    }

    /** A click on a solid neighbour's face that places a block into {@code cell}. */
    private static BlockHitResult supportHit(MinecraftClient client, BlockPos cell) {
        for (Direction dir : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.UP}) {
            BlockPos support = cell.offset(dir);
            var st = client.world.getBlockState(support);
            if (st.isAir() || !st.isSolidBlock(client.world, support)) {
                continue;
            }
            Direction face = dir.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5,
                    face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            return new BlockHitResult(hitVec, face, support, false);
        }
        return null;
    }

    /**
     * Cover solver: walk the eye->anchor line and return the first cell that (a) is
     * air, (b) is not our own body cell, (c) is not the anchor cell itself, and (d)
     * is legally placeable right now (vanilla support face to click). The blast ray
     * from the anchor to our eyes then has to bend around the obsidian, dropping the
     * exposure term of the vanilla damage formula below the self-damage ceiling.
     */
    private BlockPos findCoverPosition(MinecraftClient client, ClientPlayerEntity self, BlockPos anchor) {
        // The hand-played spot (from the player's clip): the floor cell touching the anchor
        // on our side, so the shield sits directly between us and the blast. Try the
        // dominant direction toward us first, then the other horizontal one.
        double ddx = self.getX() - (anchor.getX() + 0.5);
        double ddz = self.getZ() - (anchor.getZ() + 0.5);
        Direction major = Math.abs(ddx) >= Math.abs(ddz)
                ? (ddx > 0 ? Direction.EAST : Direction.WEST)
                : (ddz > 0 ? Direction.SOUTH : Direction.NORTH);
        Direction minor = Math.abs(ddx) >= Math.abs(ddz)
                ? (ddz > 0 ? Direction.SOUTH : Direction.NORTH)
                : (ddx > 0 ? Direction.EAST : Direction.WEST);
        for (Direction d : new Direction[]{major, minor}) {
            BlockPos cell = anchor.offset(d);
            if (client.world.getBlockState(cell).isAir()
                    && !self.getBoundingBox().intersects(new Box(cell))
                    && dev.zephyr.core.crystal.CrystalSolver.blockCellPlaceable(client.world, cell, self)) {
                return cell.toImmutable();
            }
        }

        Vec3d eye = self.getEyePos();
        Vec3d blast = Vec3d.ofCenter(anchor);
        Vec3d direction = blast.subtract(eye).normalize();
        double dist = eye.distanceTo(blast);

        for (double t = 0.8; t < dist - 0.5; t += 0.45) {
            Vec3d probe = eye.add(direction.multiply(t));
            BlockPos cell = BlockPos.ofFloored(probe);
            if (cell.equals(anchor) || cell.equals(self.getBlockPos())) {
                continue;
            }
            if (!client.world.getBlockState(cell).isAir()) {
                continue; // already covered by something solid
            }
            if (self.getBoundingBox().intersects(new Box(cell))) {
                continue; // never block our own body cell
            }
            if (!dev.zephyr.core.crystal.CrystalSolver.blockCellPlaceable(
                    client.world, cell, self)) {
                continue;
            }
            return cell.toImmutable();
        }

        // Fallback: any air neighbor of the anchor toward the player with solid support.
        for (Direction dir : Direction.values()) {
            BlockPos candidate = anchor.offset(dir);
            if (candidate.equals(self.getBlockPos())) {
                continue;
            }
            if (client.world.getBlockState(candidate).isAir()
                    && !client.world.getBlockState(candidate.down()).isAir()
                    && candidate.getSquaredDistance(self.getBlockPos())
                    < anchor.getSquaredDistance(self.getBlockPos())) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    @Override
    public String hudLabel() {
        return name + " · " + stage.name().toLowerCase();
    }
}
