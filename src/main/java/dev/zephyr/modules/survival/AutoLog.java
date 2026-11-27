package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.text.Text;

/**
 * AutoLog - disconnect safety valve. Trips on:
 * <ul>
 *   <li>health at or below the threshold (with grace period so a single hit doesn't
 *       disconnect you mid-combo unless it is lethal-scale)</li>
 *   <li>zero totems left while engaged (hostiles nearby)</li>
 *   <li>optional: live end crystal inside the panic radius while low</li>
 * </ul>
 * Disconnect goes through the vanilla network-handler disconnect path, producing the
 * same clean close a manual quit would.
 */
public final class AutoLog extends Module {

    private final NumberSetting healthThreshold = num("health", "Health threshold", 2.0, 20.0, 0.5, 4.0);
    private final NumberSetting graceTicks = num("grace", "Grace ticks", 0, 100, 5, 20);
    private final NumberSetting crystalRange = num("crystalRange", "Crystal panic range", 0.0, 10.0, 0.5, 5.0);
    private final BooleanSetting noTotems = bool("noTotems", "Log when out of totems", true);
    private final BooleanSetting onlyInCombat = bool("combat", "Only when hostiles near", true);
    private final BooleanSetting autoDisable = bool("autoDisable", "Disable module after log", true);

    private int lowHealthTicks;
    private boolean triggered;

    public AutoLog() {
        super("AutoLog", ModuleCategory.SURVIVAL, 0);
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || triggered) {
            return;
        }

        boolean low = self.getHealth() <= healthThreshold.get();
        boolean hostilesNear = !onlyInCombat.get() || hostileNearby(client, self);
        boolean outOfTotems = noTotems.get() && totemCount(self) == 0;
        boolean crystalThreat = crystalPanic(client, self);

        // Crystal panic: live crystal inside the radius while low is an instant trip.
        if (crystalThreat && low) {
            disconnect(client, "Zephyr AutoLog: live crystal in panic radius");
            return;
        }

        if (low && hostilesNear) {
            lowHealthTicks++;
            boolean lethalWindow = self.getHealth() <= 2.0f || outOfTotems;
            if (lowHealthTicks >= graceTicks.get() || (lethalWindow && lowHealthTicks >= 3)) {
                disconnect(client, reason(low, outOfTotems));
            }
        } else {
            lowHealthTicks = 0;
        }
    }

    private String reason(boolean low, boolean outOfTotems) {
        if (outOfTotems && low) {
            return "Zephyr AutoLog: low health + no totems";
        }
        if (outOfTotems) {
            return "Zephyr AutoLog: out of totems";
        }
        return "Zephyr AutoLog: low health";
    }

    private void disconnect(MinecraftClient client, String reason) {
        triggered = true;
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().getConnection().disconnect(Text.literal(reason));
        }
        if (autoDisable.get()) {
            setEnabled(false);
        }
        triggered = false;
    }

    private boolean hostileNearby(MinecraftClient client, ClientPlayerEntity self) {
        return client.world.getEntities().stream().anyMatch(e ->
                e instanceof net.minecraft.entity.player.PlayerEntity p
                        && p != self
                        && p.isAlive()
                        && !p.isSpectator()
                        && self.squaredDistanceTo(p) < 64.0);
    }

    private int totemCount(ClientPlayerEntity self) {
        int count = 0;
        for (int i = 0; i < self.getInventory().size(); i++) {
            ItemStack stack = self.getInventory().getStack(i);
            if (ItemUtil.isTotem(stack)) {
                count += stack.getCount();
            }
        }
        if (ItemUtil.isTotem(self.getOffHandStack())) {
            count += self.getOffHandStack().getCount();
        }
        return count;
    }

    @Override
    public String hudLabel() {
        return name + (lowHealthTicks > 0 ? " · armed " + lowHealthTicks + "t" : "");
    }

    private boolean crystalPanic(MinecraftClient client, ClientPlayerEntity self) {
        double rangeSq = crystalRange.get() * crystalRange.get();
        if (crystalRange.get() <= 0) {
            return false;
        }
        for (var e : client.world.getEntities()) {
            if (e instanceof EndCrystalEntity crystal
                    && crystal.squaredDistanceTo(self) <= rangeSq
                    && !!crystal.isAlive()) {
                return true;
            }
        }
        return false;
    }
}
