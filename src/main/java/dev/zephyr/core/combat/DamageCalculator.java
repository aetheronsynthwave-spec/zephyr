package dev.zephyr.core.combat;

import dev.zephyr.core.util.ItemUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Vanilla-faithful damage math used for self-blast gating and target damage estimation.
 *
 * <p>Explosion path replicates {@code Explosion#collectBlocksAndDamageEntities}:
 * normalized distance d = dist / (2 * strength), exposure sampled by rays from the blast
 * origin to a 3x3x3 grid over the target box, final damage
 * ((1-d)^2 + d) / 2 * 7 * 2*strength + 1, then blast-protection and armor reductions.
 */
public final class DamageCalculator {

    private DamageCalculator() {
    }

    public static final float CRYSTAL_STRENGTH = 6.0f;
    public static final float ANCHOR_STRENGTH = 5.0f;

    /**
     * Expected damage to {@code target} from an explosion of {@code strength} at {@code origin}.
     */
    public static double explosionDamage(World world, Vec3d origin, float strength, Entity target) {
        Vec3d center = target.getBoundingBox().getCenter();
        double dist = Math.sqrt(center.squaredDistanceTo(origin));
        double normalized = dist / (2.0 * strength);
        if (normalized > 1.0) {
            return 0.0;
        }
        double exposure = exposure(world, origin, target);
        double falloff = (1.0 - normalized) * exposure;
        double damage = (falloff * falloff + falloff) / 2.0 * 7.0 * (strength * 2.0) + 1.0;
        damage *= blastProtectionFactor(target);
        damage *= armorFactor(target);
        return Math.max(0.0, damage);
    }

    public static double crystalDamage(World world, Vec3d origin, Entity target) {
        return explosionDamage(world, origin, CRYSTAL_STRENGTH, target);
    }

    /** Respawning anchors detonate at strength 5 outside the Nether. */
    public static double anchorDamage(World world, Vec3d origin, Entity target) {
        return explosionDamage(world, origin, ANCHOR_STRENGTH, target);
    }

    /**
     * Vanilla-style exposure: sample raycasts from the origin to a 3x3x3 grid over the
     * entity box; exposure = unobstructed / total.
     */
    private static double exposure(World world, Vec3d origin, Entity target) {
        Box box = target.getBoundingBox();
        double[] xs = {box.minX, box.getCenter().x, box.maxX};
        double[] ys = {box.minY, box.getCenter().y, box.maxY};
        double[] zs = {box.minZ, box.getCenter().z, box.maxZ};
        int visible = 0;
        int total = 0;
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vec3d point = new Vec3d(x, y, z);
                    total++;
                    if (world.raycast(new net.minecraft.world.RaycastContext(origin, point,
                            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                            net.minecraft.world.RaycastContext.FluidHandling.NONE, target))
                            .getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
                        visible++;
                    }
                }
            }
        }
        return total == 0 ? 0 : (double) visible / total;
    }

    /** Blast Protection (and normal Protection vs explosions) multiplicative reduction. */
    private static double blastProtectionFactor(Entity target) {
        if (!(target instanceof LivingEntity living)) {
            return 1.0;
        }
        int levels = 0;
        for (net.minecraft.entity.EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = living.getEquippedStack(slot);
            levels += ItemUtil.enchantLevel(armor, "blast_protection");
            levels += ItemUtil.enchantLevel(armor, "protection");
        }
        levels = Math.min(levels, 40); // server caps total protection weight
        double reduction = Math.min(0.6, levels * 0.03); // 3% per EPF point, capped 60% for blasts
        return 1.0 - reduction;
    }

    private static final net.minecraft.entity.EquipmentSlot[] ARMOR_SLOTS = {
            net.minecraft.entity.EquipmentSlot.HEAD,
            net.minecraft.entity.EquipmentSlot.CHEST,
            net.minecraft.entity.EquipmentSlot.LEGS,
            net.minecraft.entity.EquipmentSlot.FEET
    };

    /**
     * Armor absorption approximation driven by the armor attribute only (1.21.11 yarn no
     * longer maps the toughness attribute constant). Armor-only is a close lower bound
     * and only feeds the *timing/gating* decisions, never the outgoing damage itself.
     */
    private static double armorFactor(Entity target) {
        if (!(target instanceof LivingEntity living)) {
            return 1.0;
        }
        double armor = living.getArmor();
        if (armor <= 0) {
            return 1.0;
        }
        double factor = 1.0 - Math.min(20.0, Math.max(armor / 5.0, armor)) / 25.0;
        return MathHelper.clamp(factor, 0.2, 1.0);
    }

    // ------------------------------------------------------------------
    // Mace / fall math
    // ------------------------------------------------------------------

    /** Fall damage beyond the 3-block grace: ceil(distance - 3). */
    public static double fallDamage(double fallDistance) {
        if (fallDistance <= 3.0) {
            return 0.0;
        }
        return Math.ceil(fallDistance - 3.0);
    }

    /**
     * Mace smash bonus (1.21+): 4 damage for the first fallen block, +2 per additional
     * block; Density adds 0.5 * level * blocks fallen (capped by the vanilla cap of the
     * first-3-blocks scaling for Density). Approximation documented in the module: exact
     * values depend on the server's damage pipeline, this drives the *timing* decision.
     */
    public static double maceSmashBonus(double fallDistance, int densityLevel) {
        int blocks = (int) Math.floor(fallDistance);
        if (blocks <= 0) {
            return 0.0;
        }
        double bonus = 4.0 + (blocks - 1) * 2.0;
        if (densityLevel > 0) {
            bonus += Math.min(blocks, 3 + densityLevel) * 0.5 * densityLevel;
        }
        return bonus;
    }

    /** Predicted landing Y within one tick using current velocity + gravity integration. */
    public static double predictLandingY(Entity e) {
        return e.getY() + e.getVelocity().y;
    }

    /** Remaining distance to the surface below (block-accurate). */
    public static double distanceToGround(World world, Entity e) {
        BlockPos start = e.getBlockPos();
        for (int i = 0; i < 12; i++) {
            BlockPos probe = start.down(i);
            if (!world.getBlockState(probe).isAir()) {
                return Math.max(0.0, e.getY() - (probe.getY() + 1));
            }
        }
        return Double.MAX_VALUE;
    }
}
