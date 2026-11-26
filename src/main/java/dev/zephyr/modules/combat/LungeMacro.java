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
    private final BooleanSetting showSwap = bool("showSwap", "Show spear swap", true);
    private final NumberSetting backTicks = num("backTicks", "Swap-back ticks", 1, 10, 1, 2);
    /** Your routine: shield (no cooldown item) first, then the spear jab, then back to it. */
    private final BooleanSetting prime = bool("prime", "Switch to no-cooldown item first", true);
    private final NumberSetting primeTicks = num("primeTicks", "Ticks on that item first", 0, 10, 1, 1);
    private final BooleanSetting returnOriginal = bool("returnOriginal", "Return to original item after", false);

    private int primeWait = -1;
    private int originalSlot = -1;
    private int returnWait = -1;

    private long lastLunge;
    private boolean pending;
    private int lunges;

    public LungeMacro() {
        super("LungeMacro", ModuleCategory.COMBAT, GLFW.GLFW_KEY_H);
    }

    @Override
    public void handleKeyPress(int keyCode) {
        if (keyCode == bind().get()) {
            if (!isEnabled()) {
                setEnabled(true); // the key always works, no need to enable it in the menu first
            }
            pending = true; // runs on the next tick, on the game thread
        }
    }

    @Override
    protected void onDisable() {
        pending = false;
    }

    /**
     * Item with no cooldown to sit on before the jab: a shield first (the hand routine),
     * else an empty slot, else any non-weapon item that is not on an item cooldown.
     */
    private int findPrimeSlot(ClientPlayerEntity self) {
        int empty = -1;
        int plain = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = self.getInventory().getStack(i);
            if (s.isOf(net.minecraft.item.Items.SHIELD)) {
                return i;
            }
            if (s.isEmpty() && empty == -1) {
                empty = i;
            } else if (plain == -1 && !s.isEmpty() && !ItemUtil.isSpear(s) && !ItemUtil.isMace(s)
                    && !ItemUtil.id(s).endsWith("_sword") && !ItemUtil.id(s).endsWith("_axe")
                    && !self.getItemCooldownManager().isCoolingDown(s)) {
                plain = i;
            }
        }
        return empty != -1 ? empty : plain;
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (returnWait >= 0 && client.player != null && !dev.zephyr.core.combat.SwapManager.pending()) {
            // "Return to original item": after the spear swap-back has finished.
            if (returnWait-- <= 0) {
                if (originalSlot != -1) {
                    dev.zephyr.core.combat.SwapManager.select(client, originalSlot);
                }
                originalSlot = -1;
                returnWait = -1;
            }
        }
        if (primeWait >= 0) {
            if (primeWait-- > 0) {
                return;
            }
            primeWait = -1;
            jab(client);
            return;
        }
        if (!pending) {
            return;
        }
        pending = false;
        ClientPlayerEntity self = client.player;
        if (self == null || client.getNetworkHandler() == null || client.currentScreen != null) {
            return;
        }
        if (System.currentTimeMillis() - lastLunge < cooldownMs.get() || findSpear(self) == -1) {
            return;
        }
        if (prime.get()) {
            int p = findPrimeSlot(self);
            if (p != -1) {
                originalSlot = self.getInventory().getSelectedSlot();
                if (p != originalSlot) {
                    dev.zephyr.core.combat.SwapManager.select(client, p);
                }
                primeWait = primeTicks.get().intValue();
                if (primeWait <= 0) {
                    primeWait = -1;
                    jab(client);
                }
                return;
            }
        }
        jab(client);
    }

    private void jab(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.interactionManager == null || client.getNetworkHandler() == null
                || client.currentScreen != null) {
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
        PiercingWeaponComponent jab = self.getInventory().getStack(slot).get(DataComponentTypes.PIERCING_WEAPON);
        if (jab == null) {
            return;
        }
        // Lunging from behind a raised shield (the hand routine): drop the block for the jab,
        // then raise it again right after if right-click is still held.
        boolean wasBlocking = self.isUsingItem();
        Hand blockHand = self.getActiveHand();
        if (wasBlocking) {
            client.interactionManager.stopUsingItem(self);
        }
        // Shield -> spear (jab fires Lunge) -> back; the spear shows for a moment when
        // "Show spear swap" is on, exactly like doing it by hand.
        int back = showSwap.get() ? backTicks.get().intValue() : 0;
        dev.zephyr.core.combat.SwapManager.withSwap(client, slot, back, () -> {
            client.interactionManager.attackWithPiercingWeapon(jab);
            self.swingHand(Hand.MAIN_HAND);
            return Boolean.TRUE;
        });
        if (wasBlocking && client.options.useKey.isPressed() && back <= 0) {
            client.interactionManager.interactItem(self, blockHand); // shield back up (same hand)
        }
        lastLunge = now;
        lunges++;
        if (returnOriginal.get() && originalSlot != -1) {
            returnWait = (showSwap.get() ? backTicks.get().intValue() : 0) + 2;
        } else {
            originalSlot = -1;
        }
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
