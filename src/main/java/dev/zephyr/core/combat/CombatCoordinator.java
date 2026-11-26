package dev.zephyr.core.combat;

import dev.zephyr.ZephyrClient;
import dev.zephyr.core.timing.TickPhase;
import dev.zephyr.modules.combat.AutoMace;
import dev.zephyr.modules.combat.ShieldBreaker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Lets the melee modules share one target without wasting hits on each other.
 *
 * <p>Priority, highest first:
 * <ol>
 *   <li><b>ShieldBreaker</b>, if the target's shield is raised toward us: a sword or mace
 *       hit into a raised shield is fully blocked.</li>
 *   <li><b>AutoMace</b>, while a slam is coming: a normal hit just before it would put the
 *       target into hurt frames and waste most of the smash.</li>
 *   <li><b>TriggerBot / LegitAura / AutoHitCrystal melee</b>: they hold their hit while
 *       either of the above wants the target.</li>
 * </ol>
 *
 * <p><b>Stun slam:</b> ShieldBreaker's axe hit disables the shield (a 5 s cooldown on the
 * server), then AutoMace smashes on the next tick (one attack per tick, see ActionState).
 * The client keeps showing the shield raised until the server's update arrives about one
 * round trip later, so the broken shield is remembered here and treated as down for that
 * long.
 */
public final class CombatCoordinator {

    private CombatCoordinator() {
    }

    private static Entity brokenShield;
    private static long brokenAtTick = Long.MIN_VALUE;

    /** ShieldBreaker landed its axe hit on {@code target}'s raised shield this tick. */
    public static void noteShieldBroken(LivingEntity target) {
        brokenShield = target;
        brokenAtTick = TickPhase.tickId();
    }

    /** True while our axe hit is known to have disabled this target's shield. */
    public static boolean shieldRecentlyBroken(Entity target) {
        if (target == null || target != brokenShield) {
            return false;
        }
        long window = 8 + 2L * pingTicks(MinecraftClient.getInstance());
        return TickPhase.tickId() - brokenAtTick <= window;
    }

    /**
     * Would a hit from {@code attacker} be blocked right now? Mirrors vanilla's shield
     * check: the target must be blocking and facing the attacker (shields cover the front
     * half only), and not just disabled by our own axe hit.
     */
    public static boolean shieldBlocks(LivingEntity target, Entity attacker) {
        if (!target.isBlocking() || shieldRecentlyBroken(target)) {
            return false;
        }
        Vec3d facing = target.getRotationVector(0.0f, target.getHeadYaw());
        double dx = target.getX() - attacker.getX();
        double dz = target.getZ() - attacker.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4) {
            return true;
        }
        // Vanilla: blocked when (target - attacker) . facing < 0, i.e. it faces us.
        return (dx / len) * facing.x + (dz / len) * facing.z < 0.0;
    }

    /** AutoMace is enabled and a slam is building on this tick. */
    public static boolean maceSlamPending() {
        AutoMace mace = module(AutoMace.class);
        return mace != null && mace.isEnabled() && mace.slamPending();
    }

    /** ShieldBreaker is enabled and wants to break this target's shield. */
    public static boolean shieldBreakerWants(MinecraftClient client, LivingEntity target) {
        ShieldBreaker sb = module(ShieldBreaker.class);
        return sb != null && sb.isEnabled() && sb.wantsToBreak(client, target);
    }

    /** Lower-priority melee modules call this before swinging. */
    public static boolean meleeShouldYield(MinecraftClient client, LivingEntity target) {
        return maceSlamPending() || shieldBreakerWants(client, target);
    }

    public static int pingTicks(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) {
            return 0;
        }
        PlayerListEntry e = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        return e == null ? 0 : Math.round(e.getLatency() / 50.0f);
    }

    private static <T extends dev.zephyr.core.Module> T module(Class<T> type) {
        return ZephyrClient.modules() == null ? null : ZephyrClient.modules().get(type);
    }
}
