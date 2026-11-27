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
        BlockPos cover = findCoverPosition(client, self, anchor);
        if (cover == null) {
            // No legal cover: hard-fail this detonation cycle rather than self-destruct.
            stage = Stage.IDLE;
            stageTimer.invalidate();
            anchorPos = null;
            return true;
        }
        int obsidianSlot = InventoryManager.findHotbarSlot(self, ItemUtil::isObsidian);
        if (obsidianSlot == -1) {
            stage = Stage.IDLE;
            stageTimer.invalidate();
            anchorPos = null;
            return true;
        }
        InventoryManager.selectHotbar(client, obsidianSlot);
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(cover).add(0, 0.45, 0), Direction.UP, cover, false);
        var result = dev.zephyr.core.combat.CombatActions.interactBlock(client, hit);
        return result.isAccepted() && client.world.getBlockState(cover).isOf(Blocks.OBSIDIAN);
    }

    /**
     * Air cell adjacent to the anchor, on the segment toward the player, with solid
     * support below - the classic shield block. Uses a vanilla raycast probe from the
     * eye to the anchor to find the first obstructed-air neighbor on the line.
     */
    private BlockPos findCoverPosition(MinecraftClient client, ClientPlayerEntity self, BlockPos anchor) {
        Vec3d eye = self.getEyePos();
        Vec3d blast = Vec3d.ofCenter(anchor);
        Vec3d direction = blast.subtract(eye).normalize();

        // Sample the eye->anchor line; the first air block whose cell intersects the line
        // and sits lower than the eye gets the cover placement.
        for (double t = 1.2; t < eye.distanceTo(blast); t += 0.6) {
            Vec3d probe = eye.add(direction.multiply(t));
            BlockPos cell = BlockPos.ofFloored(probe);
            if (!cell.equals(anchor)
                    && client.world.getBlockState(cell).isAir()
                    && !client.world.getBlockState(cell.up()).isOf(Blocks.RESPAWN_ANCHOR)
                    && self.getBoundingBox().intersects(new Box(cell))) {
                continue; // never block our own body cell
            }
            if (!cell.equals(anchor)
                    && client.world.getBlockState(cell).isAir()
                    && !client.world.getBlockState(cell.up()).isAir()) {
                continue;
            }
            if (!cell.equals(anchor) && client.world.getBlockState(cell).isAir()) {
                return cell.toImmutable();
            }
        }

        // Fallback: any air neighbor of the anchor toward the player with support.
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
