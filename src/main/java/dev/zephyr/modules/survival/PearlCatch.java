package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.CombatActions;
import dev.zephyr.core.combat.CombatCoordinator;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.timing.MsTimer;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * PearlCatch - throw a pearl up, then hit it in mid-air with a wind charge. Both throws are
 * silent: the rotation only rides on {@code PlayerInteractItemC2SPacket} (the server applies
 * it before using the item), so your camera never moves.
 *
 * <p><b>Intercept math</b> (vanilla 1.21.11 per-tick physics):
 * <ul>
 *   <li>Pearl ({@code ThrownEntity}): {@code pos += v; v *= 0.99; v.y -= 0.03}.</li>
 *   <li>Wind charge: no gravity, drag 1.0, no acceleration - a straight line at constant
 *       velocity {@code 1.5 * dir + shooterMovement} from the eyes (shooter movement is added
 *       by {@code ProjectileEntity#setVelocity}; vertical part only when airborne).</li>
 *   <li>Wind after k ticks: {@code eye + m*k + 1.5*k*dir}, so it meets the pearl at tick k when
 *       {@code |P(k) - eye - m*k| = 1.5k}. The first zero crossing of
 *       {@code f(k) = |P(k) - eye - m*k| - 1.5k} is interpolated between ticks and the
 *       direction to that point is the aim.</li>
 *   <li>Latency: the client sees the pearl one one-way trip late, and the server spawns the
 *       wind charge one one-way trip after we throw, so wind tick k meets the pearl state
 *       {@code k + RTT/50} ticks ahead of what the client shows now.</li>
 * </ul>
 * Old bugs fixed: shooter movement was added to the distance instead of being solved out, no
 * latency offset, whole-tick aim snapping (no interpolation), and a visible rotation that had
 * to "arrive" before throwing, which burned the intercept window.
 */
public final class PearlCatch extends Module {

    private static final double PEARL_DRAG = 0.99;
    private static final double PEARL_GRAVITY = 0.03;
    private static final double WIND_SPEED = 1.5;
    private static final int HORIZON = 80;

    private final NumberSetting lookPitch = num("lookPitch", "Pearl pitch (90 = straight up)", 45, 90, 1, 90);
    private final NumberSetting minGap = num("minGap", "Min ticks pearl->wind", 1, 10, 1, 2);
    private final NumberSetting tickOffset = num("tickOffset", "Timing offset (ticks)", -3, 3, 1, 0);
    private final NumberSetting restoreMin = num("restMin", "Restore delay min ms", 20, 500, 5, 60);
    private final NumberSetting restoreMax = num("restMax", "Restore delay max ms", 20, 800, 5, 160);

    private enum Stage { IDLE, PEARL, WIND, RESTORE }

    private Stage stage = Stage.IDLE;
    private final MsTimer stageTimer = new MsTimer();
    private int returnSlot = -1;
    private int stageTicks;
    private final double[] px = new double[HORIZON + 2];
    private final double[] py = new double[HORIZON + 2];
    private final double[] pz = new double[HORIZON + 2];
    private float aimYaw;
    private float aimPitch;

    public PearlCatch() {
        super("PearlCatch", ModuleCategory.SURVIVAL, GLFW.GLFW_KEY_G);
    }

    @Override
    public void handleKeyPress(int keyCode) {
        if (keyCode != bind().get()) {
            return;
        }
        if (!isEnabled()) {
            setEnabled(true);
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
            case WIND -> windStage(client, self);
            case RESTORE -> restoreStage(client);
        }
    }

    private void pearlStage(MinecraftClient client, ClientPlayerEntity self) {
        if (stageTicks > 20) {
            beginRestore();
            return;
        }
        if (!holdItem(client, self, ItemUtil::isPearl) || ItemUtil.coolingDown(self, self.getMainHandStack())) {
            return;
        }
        if (CombatActions.interactItemAt(client, self.getYaw(), (float) -lookPitch.get()).isAccepted()) {
            stageTicks = 0;
            stage = Stage.WIND;
        }
    }

    private void windStage(MinecraftClient client, ClientPlayerEntity self) {
        if (stageTicks > 40) {
            beginRestore(); // pearl never seen / no intercept left
            return;
        }
        boolean ready = holdItem(client, self, ItemUtil::isWindCharge)
                && !ItemUtil.coolingDown(self, self.getMainHandStack());
        EnderPearlEntity pearl = findOwnPearl(client, self);
        if (!ready || pearl == null || stageTicks < minGap.get()) {
            return;
        }
        int lead = Math.max(0, CombatCoordinator.pingTicks(client) + tickOffset.get().intValue());
        if (!solve(self, pearl, lead)) {
            return;
        }
        if (CombatActions.interactItemAt(client, aimYaw, aimPitch).isAccepted()) {
            beginRestore();
        }
    }

    /** Fills aimYaw/aimPitch with the interception direction; false if none in range. */
    private boolean solve(ClientPlayerEntity self, EnderPearlEntity pearl, int lead) {
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
        Vec3d eye = self.getEyePos();
        Vec3d mv = self.getVelocity();
        double mx = mv.x, my = self.isOnGround() ? 0.0 : mv.y, mz = mv.z;
        double prevF = Double.NaN;
        for (int k = 0; k + lead <= HORIZON + 1; k++) {
            int i = k + lead;
            double rx = px[i] - eye.x - mx * k, ry = py[i] - eye.y - my * k, rz = pz[i] - eye.z - mz * k;
            double f = Math.sqrt(rx * rx + ry * ry + rz * rz) - WIND_SPEED * k;
            if (k > 0 && prevF > 0 && f <= 0) {
                double t = prevF / (prevF - f); // fraction between tick k-1 and k
                double kk = k - 1 + t;
                int j = i - 1;
                double ax = px[j] + (px[i] - px[j]) * t - eye.x - mx * kk;
                double ay = py[j] + (py[i] - py[j]) * t - eye.y - my * kk;
                double az = pz[j] + (pz[i] - pz[j]) * t - eye.z - mz * kk;
                double h = Math.sqrt(ax * ax + az * az);
                aimYaw = (float) (MathHelper.atan2(az, ax) * MathHelper.DEGREES_PER_RADIAN) - 90f;
                aimPitch = (float) -(MathHelper.atan2(ay, h) * MathHelper.DEGREES_PER_RADIAN);
                return true;
            }
            prevF = f;
        }
        return false;
    }

    private void restoreStage(MinecraftClient client) {
        if (!stageTimer.isDue()) {
            return;
        }
        if (returnSlot != -1 && !InventoryManager.selectHotbar(client, returnSlot)) {
            return;
        }
        stage = Stage.IDLE;
        stageTimer.invalidate();
    }

    private void beginRestore() {
        stageTimer.restart(restoreMin.get(), restoreMax.get());
        stage = Stage.RESTORE;
    }

    private EnderPearlEntity findOwnPearl(MinecraftClient client, ClientPlayerEntity self) {
        EnderPearlEntity best = null;
        for (EnderPearlEntity ep : client.world.getEntitiesByClass(EnderPearlEntity.class,
                self.getBoundingBox().expand(96.0), x -> x.getOwner() == self && !x.isRemoved())) {
            if (best == null || ep.age < best.age) {
                best = ep;
            }
        }
        return best;
    }

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
        if (handler != -1 && swapSlot != -1) {
            InventoryManager.swapWithHotbar(client, handler, swapSlot);
        }
        return false;
    }

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
        return name + " · " + stage.name().toLowerCase();
    }
}
