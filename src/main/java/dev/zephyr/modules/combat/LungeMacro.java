package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

/**
 * LungeMacro - one key, one attribute-swapped spear jab.
 *
 * <p>In 1.21.11 a spear's left-click is a piercing jab (its own attack, not a normal entity
 * hit), and that jab is what fires the Lunge enchantment. On the bind this module, inside a
 * single client tick: selects the hotbar spear, performs the jab, and re-selects your
 * current item. The server runs the jab with the spear (Lunge launch + spear damage) while
 * you visibly keep holding your weapon.
 *
 * <p>The bind is a trigger, not a toggle: enable the module, then press the key to lunge.
 */
public final class LungeMacro extends Module {

    private final BooleanSetting preferLunge = bool("preferLunge", "Prefer Lunge-enchanted spear", true);
    private final NumberSetting cooldownMs = num("cooldown", "Min ms between lunges", 0, 1000, 10, 150);

    private long lastLunge;
    private boolean pending;
    private int lunges;

    public LungeMacro() {
        super("LungeMacro", ModuleCategory.COMBAT, GLFW.GLFW_KEY_H);
    }

    @Override
    public void handleKeyPress(int keyCode) {
        if (keyCode == bind().get() && isEnabled()) {
            pending = true; // runs on the next tick, on the game thread
        }
    }

    @Override
    protected void onDisable() {
        pending = false;
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (!pending) {
            return;
        }
        pending = false;
        ClientPlayerEntity self = client.player;
        if (self == null || client.interactionManager == null || client.getNetworkHandler() == null
                || client.currentScreen != null || self.isUsingItem()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLunge < cooldownMs.get()) {
            return;
        }
        int slot = findSpear(self);
        if (slot == -1) {
            return;
        }
        ItemStack spear = self.getInventory().getStack(slot);
        PiercingWeaponComponent jab = spear.get(DataComponentTypes.PIERCING_WEAPON);
        if (jab == null) {
            return;
        }
        var inv = self.getInventory();
        int original = inv.getSelectedSlot();
        if (original != slot) {
            inv.setSelectedSlot(slot);
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
        client.interactionManager.attackWithPiercingWeapon(jab);
        self.swingHand(Hand.MAIN_HAND);
        if (original != slot) {
            inv.setSelectedSlot(original);
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(original));
        }
        lastLunge = now;
        lunges++;
    }

    private int findSpear(ClientPlayerEntity self) {
        int any = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = self.getInventory().getStack(i);
            if (!ItemUtil.isSpear(s) || !s.contains(DataComponentTypes.PIERCING_WEAPON)) {
                continue;
            }
            if (!preferLunge.get() || ItemUtil.enchantLevel(s, "lunge") > 0) {
                return i;
            }
            if (any == -1) {
                any = i;
            }
        }
        return any;
    }

    @Override
    public String hudLabel() {
        return name + " · " + lunges;
    }
}
