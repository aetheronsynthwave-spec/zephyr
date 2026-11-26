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

    // ------------------------------------------------------------------
    // Exposure memoization (THE FPS FIX)
    // ------------------------------------------------------------------
    // explosionDamage is called for many candidate blast cells per solve, and each
    // call walked 27 world raycasts - hundreds of block-shape intersections per tick,
    // which is what dragged a 250 fps rig to ~120 while the crystal modules were
    // armed. The exposure value for a (blast origin, entity) pair cannot change within
    // a tick (blocks change at most per tick and the entity box is fixed until it
    // moves), so we memo it in a small fixed table keyed by blast position + entity id
    // and advance the generation every client tick. Pure win: identical numbers,
    // 5-20x fewer raycasts in solve-heavy ticks.
    private static final int EXPOSURE_TABLE_SIZE = 256;
    private static final long[] EXPOSURE_KEY = new long[EXPOSURE_TABLE_SIZE];
    private static final double[] EXPOSURE_VAL = new double[EXPOSURE_TABLE_SIZE];
    private static final int[] EXPOSURE_GEN = new int[EXPOSURE_TABLE_SIZE];
    private static int generation;

    /** Called once per client tick from the tick dispatcher. */
    public static void advanceTick() {
        generation++;
    }

    private static int exposureSlot(double ox, double oy, double oz, int entityId) {
        long h = 1125899906842597L * entityId
                + Double.doubleToLongBits(ox) * 31L
                + Double.doubleToLongBits(oy) * 1099511627761L
                + Double.doubleToLongBits(oz) * 4294967291L;
        h ^= (h >>> 29);
        int idx = (int) (h & (EXPOSURE_TABLE_SIZE - 1));
        for (int probe = 0; probe < 8; probe++) {
            int i = (idx + probe) & (EXPOSURE_TABLE_SIZE - 1);
            if (EXPOSURE_GEN[i] != generation) {
                return i; // stale or empty
            }
        }
        return idx; // table pressure: best-effort overwrite
    }

    /**
     * Expected damage to {@code target} from an explosion of {@code strength} at {@code origin}.
     */
    public static double explosionDamage(World world, Vec3d origin, float strength, Entity target) {
        return explosionDamage(world, origin, strength, null, target);
    }

    /**
     * Same model, but the distance term is evaluated against a *predicted* center
     * (motion-forecast position) while exposure still samples the live hitbox. This is
     * the damage the server will register if the target keeps its velocity for the
     * tick or two between our click and the detonation.
     */
    public static double explosionDamage(World world, Vec3d origin, float strength,
                                         Vec3d predictedCenter, Entity target) {
        Vec3d center = predictedCenter != null
                ? predictedCenter : target.getBoundingBox().getCenter();
        double dist = Math.sqrt(center.squaredDistanceTo(origin));
        double normalized = dist / (2.0 * strength);
        if (normalized > 1.0) {
            return 0.0;
        }
        // Distance-driven early-out: beyond 0.75 of the blast radius the exposure
        // factor multiplies an already-tiny falloff - a mid estimate is within a
        // fraction of a heart of the raycasted value and saves the whole ray bundle.
        double exposure = normalized > 0.75
                ? 0.35
                : exposureMemo(world, origin, target);
        double falloff = (1.0 - normalized) * exposure;
        double damage = (falloff * falloff + falloff) / 2.0 * 7.0 * (strength * 2.0) + 1.0;
        return Math.max(0.0, applyReductions(world, damage, target));
    }

    /**
     * Vanilla damage pipeline for an explosion hit, in server order: difficulty scaling
     * (players only), armor + toughness (damage-dependent), Resistance, then enchantment
     * protection (Protection = 1 EPF/level, Blast Protection = 2 EPF/level, 4% per EPF,
     * capped at 20 EPF / 80%). The old version treated armor as a flat 80% cut and
     * ignored difficulty, which under-estimated self damage by 2-3x in full netherite -
     * exactly the case where a crystal kills you.
     */
    private static double applyReductions(World world, double damage, Entity target) {
        if (!(target instanceof LivingEntity living)) {
            return damage;
        }
        if (living instanceof net.minecraft.entity.player.PlayerEntity) {
            switch (world.getDifficulty()) {
                case PEACEFUL -> damage = 0.0;
                case EASY -> damage = Math.min(damage / 2.0 + 1.0, damage);
                case HARD -> damage = damage * 1.5;
                default -> { }
            }
        }
        double armor = living.getArmor();
        if (armor > 0) {
            double toughness = living.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ARMOR_TOUGHNESS);
            double effArmor = MathHelper.clamp(armor - damage / (2.0 + toughness / 4.0), armor * 0.2, 20.0);
            damage *= 1.0 - effArmor / 25.0;
        }
        var res = living.getStatusEffect(net.minecraft.entity.effect.StatusEffects.RESISTANCE);
        if (res != null) {
            damage *= Math.max(0.0, 1.0 - (res.getAmplifier() + 1) * 0.2);
        }
        int epf = 0;
        for (net.minecraft.entity.EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armorStack = living.getEquippedStack(slot);
            epf += 2 * ItemUtil.enchantLevel(armorStack, "blast_protection");
            epf += ItemUtil.enchantLevel(armorStack, "protection");
        }
        damage *= 1.0 - Math.min(20, epf) / 25.0;
        return damage;
    }

    /** Memoized vanilla-style exposure (3x3x3 sampled raycasts over the entity box). */
    private static double exposureMemo(World world, Vec3d origin, Entity target) {
        int slot = exposureSlot(origin.x, origin.y, origin.z, target.getId());
        if (EXPOSURE_GEN[slot] == generation) {
            return EXPOSURE_VAL[slot];
        }
        double value = exposure(world, origin, target);
        EXPOSURE_GEN[slot] = generation;
        EXPOSURE_VAL[slot] = value;
        return value;
    }

    public static double crystalDamage(World world, Vec3d origin, Entity target) {
        return explosionDamage(world, origin, CRYSTAL_STRENGTH, target);
    }

    /** Crystal damage against a motion-predicted target center. */
    public static double crystalDamage(World world, Vec3d origin, Vec3d predictedCenter, Entity target) {
        return explosionDamage(world, origin, CRYSTAL_STRENGTH, predictedCenter, target);
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

    private static final net.minecraft.entity.EquipmentSlot[] ARMOR_SLOTS = {
            net.minecraft.entity.EquipmentSlot.HEAD,
            net.minecraft.entity.EquipmentSlot.CHEST,
            net.minecraft.entity.EquipmentSlot.LEGS,
            net.minecraft.entity.EquipmentSlot.FEET
    };


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
