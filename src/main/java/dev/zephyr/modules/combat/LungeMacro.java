package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.util.ItemUtil;
import dev.zephyr.core.combat.SwapManager;
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
 * hit), and that jab is what fires the Lunge enchantment. On the bind this module selects
 * the hotbar spear and performs the jab in one pre-movement stage, then re-selects your
 * item on a later tick (SwapManager allows one slot change per tick). With "prime" on, the
 * no-cooldown item gets its own tick first: prime (tick N) -> spear + jab (tick
 * N + primeTicks) -> back (later). Never a same-tick multi-slot burst.
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
    private final NumberSetting primeTicks = num("primeTicks", "Ticks on that item first", 1, 10, 1, 1);
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
        if (returnWait >= 0 && client.player != null && !SwapManager.pending()) {
            // "Return to original item": after the spear swap-back has finished. A refused
            // select (slot already changed this tick) keeps returnWait at 0 and retries.
            if (returnWait > 0) {
                returnWait--;
            } else if (originalSlot == -1 || SwapManager.select(client, originalSlot)) {
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
                int current = self.getInventory().getSelectedSlot();
                if (p != current) {
                    if (!SwapManager.select(client, p)) {
                        pending = true; // slot budget used this tick: retry the press next tick
                        return;
                    }
                    originalSlot = current;
                    // The jab needs its own tick: prime + spear in one tick is a two-slot burst.
                    primeWait = Math.max(1, primeTicks.get().intValue());
                    return;
                }
                originalSlot = current; // already on the prime item: jab right away
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
        if (self.getInventory().getSelectedSlot() != slot && SwapManager.changedThisTick()) {
            primeWait = 0; // slot already changed this tick: jab on the next pre-movement stage
            return;
        }
        // Lunging from behind a raised shield (the hand routine): drop the block for the jab.
        // Vanilla re-raises it by itself: handleInputEvents calls doItemUse again while
        // right-click is held and the player is no longer using an item.
        if (self.isUsingItem()) {
            client.interactionManager.stopUsingItem(self);
        }
        // Spear (jab fires Lunge) -> back on a later tick; the spear shows for a moment when
        // "Show spear swap" is on, exactly like doing it by hand.
        int back = showSwap.get() ? backTicks.get().intValue() : 0;
        boolean jabbed = SwapManager.withSwap(client, slot, back, () -> {
            client.interactionManager.attackWithPiercingWeapon(jab);
            self.swingHand(Hand.MAIN_HAND);
            return true;
        });
        if (!jabbed) {
            originalSlot = -1;
            return;
        }
        lastLunge = now;
        lunges++;
        if (returnOriginal.get() && originalSlot != -1) {
            returnWait = 2; // counted only after the spear swap-back has completed
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
