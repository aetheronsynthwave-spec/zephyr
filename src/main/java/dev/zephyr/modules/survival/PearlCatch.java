package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * PearlCatch - throw a pearl straight up and catch it yourself with a wind-charge jump.
 * Your camera never moves: both throws are silent (the rotation rides only on the
 * {@code PlayerInteractItemC2SPacket}, which the server applies before using the item).
 *
 * <pre>
 *  PEARL   select pearl, throw it at (your yaw, -lookPitch)          [silent]
 *  TIMING  pre-select the wind charge; every tick, simulate both bodies with vanilla
 *          physics and find the earliest tick to fire so that your launch path
 *          crosses the pearl's path
 *  FIRE    throw the wind charge straight down at your feet (pitch +90) [silent]
 *  WATCH   measure the real launch (delay, speed) to calibrate the next catch
 *  RESTORE back to the slot you held before
 * </pre>
 *
 * <p><b>Why the old math was wrong.</b> It aimed the wind charge AT the pearl. A wind charge
 * does not collide with pearls, so it flew past; and nothing lifted the player. The catch
 * works the other way round: the wind burst at your feet launches you, and the pearl hits
 * you (its owner) once your hitbox crosses its path.
 *
 * <p><b>Physics used</b> (1.21.11, per tick):
 * <ul>
 *   <li>Pearl ({@code ThrownEntity#tick}): {@code pos += v; v *= 0.99; v.y -= 0.03}.</li>
 *   <li>Player in the air: {@code pos += v; v.y = (v.y - 0.08) * 0.98; v.xz *= 0.91}.</li>
 *   <li>Wind-charge burst: power 1.2 (radius 2.4), knockback x1.22 scaled by
 *       {@code 1 - distance/2.4}; at your feet that is a launch of about 1.15 blocks/tick
 *       up, which is the default until a real launch has been measured.</li>
 *   <li>Latency: the server checks the pearl against where it last saw you. The client sees
 *       the pearl one one-way trip late and the server sees you one one-way trip late, so
 *       player state at tick m is compared with pearl state at tick m + RTT (in ticks).
 *       The fire-to-launch delay is measured on every catch and reused.</li>
 * </ul>
 */
public final class PearlCatch extends Module {

    private static final double PEARL_DRAG = 0.99;
    private static final double PEARL_GRAVITY = 0.03;
    private static final double PLAYER_GRAVITY = 0.08;
    private static final double PLAYER_DRAG_Y = 0.98;
    private static final double PLAYER_DRAG_XZ = 0.91;
    /** Player hitbox half-width / height plus projectile hit margin (0.3) and pearl half-size. */
    private static final double HALF_W = 0.3 + 0.3 + 0.125;
    private static final double HEIGHT = 1.8;
    private static final double MARGIN_Y = 0.3 + 0.125;
    private static final int HORIZON = 200;
    private static final int FLIGHT_TICKS = 45;

    private final NumberSetting lookPitch = num("lookPitch", "Throw pitch (90 = straight up)", 45, 90, 1, 90);
    private final NumberSetting maxWait = num("maxWait", "Max wait for catch (ticks)", 20, 160, 5, 120);
    private final NumberSetting restoreMin = num("restMin", "Restore delay min ms", 20, 500, 5, 60);
    private final NumberSetting restoreMax = num("restMax", "Restore delay max ms", 20, 800, 5, 160);

    private enum Stage { IDLE, PEARL, TIMING, WATCH, RESTORE }

    private Stage stage = Stage.IDLE;
    private final MsTimer stageTimer = new MsTimer();
    private int returnSlot = -1;
    private int stageTicks;
    private int thrownPearlAge = -1;
    private double lastVy;

    /** Calibration (session-wide): ticks from fire to launch minus ping ticks, and launch speed. */
    private static double delayOffset = 3.0;
    private static double launchVy = 1.15;
    private static int calibratedSamples;

    /** Reused simulation buffers (no per-tick allocation). */
    private final double[] px = new double[HORIZON + 2];
    private final double[] py = new double[HORIZON + 2];
    private final double[] pz = new double[HORIZON + 2];

    public PearlCatch() {
        super("PearlCatch", ModuleCategory.SURVIVAL, GLFW.GLFW_KEY_G);
    }

    @Override
    public void handleKeyPress(int keyCode) {
        if (keyCode != bind().get()) {
            return;
        }
        if (!isEnabled()) {
            setEnabled(true); // the key always works; it arms the module, then fires
        }
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (self != null && stage == Stage.IDLE) {
            returnSlot = InventoryManager.selectedHotbar(self);
            stageTicks = 0;
            stage = Stage.PEARL;
        }
    }

    @Override
    protected void onDisable() {
        stage = Stage.IDLE;
        stageTimer.invalidate();
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) {
            stage = Stage.IDLE;
            return;
        }
        stageTicks++;
        switch (stage) {
            case IDLE -> { }
            case PEARL -> pearlStage(client, self);
            case TIMING -> timingStage(client, self);
            case WATCH -> watchStage(self);
            case RESTORE -> restoreStage(client);
        }
        lastVy = self.getVelocity().y;
    }

    // ------------------------------------------------------------------
    // Stages
    // ------------------------------------------------------------------

    private void pearlStage(MinecraftClient client, ClientPlayerEntity self) {
        if (stageTicks > 20) {
            beginRestore(); // no pearl / never off cooldown
            return;
        }
        if (!holdItem(client, self, ItemUtil::isPearl)) {
            return; // selection lands next tick (one slot change per tick)
        }
        ItemStack held = self.getMainHandStack();
        if (ItemUtil.coolingDown(self, held)) {
            return;
        }
        if (CombatActions.interactItemAt(client, self.getYaw(), (float) -lookPitch.get()).isAccepted()) {
            thrownPearlAge = -1;
            stageTicks = 0;
            stage = Stage.TIMING;
        }
    }

    private void timingStage(MinecraftClient client, ClientPlayerEntity self) {
        if (stageTicks > maxWait.get()) {
            beginRestore();
            return;
        }
        // Wind charge in hand early, so the fire tick needs no slot change.
        boolean windReady = holdItem(client, self, ItemUtil::isWindCharge)
                && !ItemUtil.coolingDown(self, self.getMainHandStack());

        EnderPearlEntity pearl = findOwnPearl(client, self);
        if (pearl == null) {
            if (thrownPearlAge >= 0 || stageTicks > 20) {
                beginRestore(); // pearl landed / never arrived
            }
            return; // spawn packet still on its way
        }
        thrownPearlAge = pearl.age;

        int pingTicks = pingTicks(client, self);
        int launchDelay = Math.max(1, (int) Math.round(pingTicks + delayOffset));
        int fireIn = solveFireDelay(self, pearl, pingTicks, launchDelay);
        if (fireIn < 0) {
            // No reachable intercept. Give up once the pearl is falling below jump height.
            if (pearl.getVelocity().y < 0 && pearl.getY() < self.getY() + 8.0) {
                beginRestore();
            }
            return;
        }
        if (fireIn == 0 && windReady && self.isOnGround()) {
            // Straight down at the feet: the burst is centred under you, launch is vertical.
            if (CombatActions.interactItemAt(client, self.getYaw(), 90f).isAccepted()) {
                stageTicks = 0;
                stage = Stage.WATCH;
            }
        }
    }

    /** Measures the real launch to calibrate delay and launch speed. */
    private void watchStage(ClientPlayerEntity self) {
        double vy = self.getVelocity().y;
        if (vy - lastVy > 0.5) {
            MinecraftClient client = MinecraftClient.getInstance();
            int pingTicks = pingTicks(client, self);
            double measuredOffset = stageTicks - pingTicks;
            double k = calibratedSamples == 0 ? 1.0 : 0.4;
            delayOffset += (measuredOffset - delayOffset) * k;
            launchVy += (vy - launchVy) * k;
            calibratedSamples++;
            beginRestore();
        } else if (stageTicks > 30) {
            beginRestore(); // launch never seen (not on ground / burst blocked)
        }
    }

    private void restoreStage(MinecraftClient client) {
        if (!stageTimer.isDue()) {
            return;
        }
        if (returnSlot != -1 && !InventoryManager.selectHotbar(client, returnSlot)) {
            return; // slot already changed this tick: restore next tick
        }
        stage = Stage.IDLE;
        stageTimer.invalidate();
    }

    private void beginRestore() {
        stageTimer.restart(restoreMin.get(), restoreMax.get());
        stage = Stage.RESTORE;
    }

    // ------------------------------------------------------------------
    // Intercept solver
    // ------------------------------------------------------------------

    /**
     * Earliest number of ticks from now at which throwing the wind charge makes the player's
     * launch path intersect the pearl, or -1 if none within {@link #maxWait}.
     */
    private int solveFireDelay(ClientPlayerEntity self, EnderPearlEntity pearl, int pingTicks, int launchDelay) {
        // Pearl path, index m = after m client ticks from now.
        Vec3d p = pearl.getEntityPos();
        Vec3d v = pearl.getVelocity();
        double x = p.x, y = p.y, z = p.z, vx = v.x, vy = v.y, vz = v.z;
        for (int m = 0; m <= HORIZON + 1; m++) {
            px[m] = x;
            py[m] = y;
            pz[m] = z;
            x += vx;
            y += vy;
            z += vz;
            vx *= PEARL_DRAG;
            vy = vy * PEARL_DRAG - PEARL_GRAVITY;
            vz *= PEARL_DRAG;
        }
        double fx = self.getX(), fy = self.getY(), fz = self.getZ();
        double hvx = self.getVelocity().x, hvz = self.getVelocity().z;
        int maxD = Math.min(maxWait.get().intValue() - stageTicks, HORIZON);
        for (int d = 0; d <= maxD; d++) {
            int launch = d + launchDelay; // movement number launch+1 uses the launch velocity
            double sx = fx, sy = fy, sz = fz;
            double svx = hvx, svy = launchVy, svz = hvz;
            for (int m = launch + 1; m <= launch + FLIGHT_TICKS; m++) {
                int k = m + pingTicks;
                if (k + 1 > HORIZON + 1) {
                    return -1; // past the simulated pearl horizon: later d only goes further
                }
                double nx = sx + svx, ny = sy + svy, nz = sz + svz;
                // Pearl sweep during the server tick that sees the player at (nx, ny, nz).
                if (segmentHitsBox(px[k], py[k], pz[k], px[k + 1], py[k + 1], pz[k + 1], nx, ny, nz)) {
                    return d;
                }
                sx = nx;
                sy = ny;
                sz = nz;
                svy = (svy - PLAYER_GRAVITY) * PLAYER_DRAG_Y;
                svx *= PLAYER_DRAG_XZ;
                svz *= PLAYER_DRAG_XZ;
                if (sy < fy) {
                    break; // landed again
                }
            }
        }
        return -1;
    }

    /** Slab test: does the segment a->b touch the (margin-expanded) player box at feet (fx, fy, fz)? */
    private final double[] slabT = new double[2];

    private boolean segmentHitsBox(double ax, double ay, double az, double bx, double by, double bz,
                                   double fx, double fy, double fz) {
        double[] t = slabT;
        t[0] = 0.0;
        t[1] = 1.0;
        return slab(ax, bx, fx - HALF_W, fx + HALF_W, t)
                && slab(ay, by, fy - MARGIN_Y, fy + HEIGHT + MARGIN_Y, t)
                && slab(az, bz, fz - HALF_W, fz + HALF_W, t);
    }

    private static boolean slab(double a, double b, double min, double max, double[] t) {
        double d = b - a;
        if (Math.abs(d) < 1.0e-9) {
            return a >= min && a <= max;
        }
        double t1 = (min - a) / d, t2 = (max - a) / d;
        if (t1 > t2) {
            double tmp = t1;
            t1 = t2;
            t2 = tmp;
        }
        t[0] = Math.max(t[0], t1);
        t[1] = Math.min(t[1], t2);
        return t[0] <= t[1];
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int pingTicks(MinecraftClient client, ClientPlayerEntity self) {
        if (client.getNetworkHandler() == null) {
            return 0;
        }
        PlayerListEntry e = client.getNetworkHandler().getPlayerListEntry(self.getUuid());
        return e == null ? 0 : Math.round(e.getLatency() / 50.0f);
    }

    /** Our newest pearl (the one this sequence threw). */
    private EnderPearlEntity findOwnPearl(MinecraftClient client, ClientPlayerEntity self) {
        EnderPearlEntity best = null;
        for (EnderPearlEntity ep : client.world.getEntitiesByClass(EnderPearlEntity.class,
                self.getBoundingBox().expand(96.0), x -> x.getOwner() == self && !x.isRemoved())) {
            if (best == null || ep.age < best.age) {
                best = ep;
            }
        }
        if (best != null && thrownPearlAge >= 0 && best.age < thrownPearlAge) {
            return null; // a newer pearl is not ours to track; ours is gone
        }
        return best;
    }

    /** True once a hotbar slot holding the item is selected (swaps from inventory if needed). */
    private boolean holdItem(MinecraftClient client, ClientPlayerEntity self,
                             java.util.function.Predicate<ItemStack> pred) {
        if (pred.test(self.getMainHandStack())) {
            return true;
        }
        int slot = InventoryManager.findHotbarSlot(self, pred);
        if (slot != -1) {
            return InventoryManager.selectHotbar(client, slot) && pred.test(self.getMainHandStack());
        }
        int handler = InventoryManager.findHandlerSlot(self, pred);
        int swapSlot = pickSacrificialSlot(self);
        if (handler == -1 || swapSlot == -1) {
            return false;
        }
        InventoryManager.swapWithHotbar(client, handler, swapSlot);
        return false; // selected on a later tick, once the swap has landed
    }

    /** Hotbar slot for inventory swaps - never the pre-trigger "return" slot. */
    private int pickSacrificialSlot(ClientPlayerEntity self) {
        for (int i = 8; i >= 0; i--) {
            if (i != returnSlot && self.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 8; i >= 0; i--) {
            if (i != returnSlot) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String hudLabel() {
        return name + " · " + stage.name().toLowerCase()
                + (calibratedSamples > 0 ? String.format(" · %.2f", launchVy) : "");
    }
}
