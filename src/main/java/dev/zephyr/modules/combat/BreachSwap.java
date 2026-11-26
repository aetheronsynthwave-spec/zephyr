package dev.zephyr.modules.combat;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.SwapManager;
import dev.zephyr.core.inventory.InventoryManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * BreachSwap - modelled on the player's clip: you hold your sword, and every melee hit is
 * attribute-swapped to the Breach mace (Breach ignores armour), the mace shows for a
 * moment, then you are back on the sword. Works for your own clicks AND for hits from
 * other modules (LegitAura, TriggerBot, ...), because it hooks the attack itself.
 *
 * <p>Hook: {@code ClientPlayerInteractionManager#attackEntity} HEAD (see
 * MixinClientPlayerInteractionManager) calls {@link #beforeAttack}; the slot change is
 * sent before the attack packet, so the server applies the hit with the mace. The hook
 * fires wherever the attack itself happens - vanilla {@code handleInputEvents} for your own
 * clicks, the pre-movement stage for module hits - so the swap is always pre-movement.
 * If SwapManager refuses the swap this tick, the original hit goes out unchanged.
 */
public final class BreachSwap extends Module {

    private final BooleanSetting onlyBreach = bool("onlyBreach", "Require Breach enchant", true);
    private final BooleanSetting playersOnly = bool("playersOnly", "Only on players", true);
    private final BooleanSetting weaponOnly = bool("weaponOnly", "Only holding sword/axe", true);
    private final BooleanSetting showSwap = bool("showSwap", "Show mace swap", true);
    private final NumberSetting backTicks = num("backTicks", "Swap-back ticks", 1, 10, 1, 3);

    private int swaps;

    public BreachSwap() {
        super("BreachSwap", ModuleCategory.COMBAT, 0);
    }

    /** @return the hotbar slot to swap to for this hit, or -1 to leave the hit alone. */
    private int maceSlotFor(ClientPlayerEntity self, Entity target) {
        if (!(target instanceof LivingEntity) || (playersOnly.get() && !(target instanceof PlayerEntity))) {
            return -1;
        }
        // SMART: Breach only pays off against armour; on a naked target the sword hits harder.
        if (smart() && target instanceof LivingEntity le && le.getArmor() < 8) {
            return -1;
        }
        ItemStack held = self.getMainHandStack();
        if (ItemUtil.isMace(held)) {
            return -1; // already a mace (e.g. AutoMace's own swap)
        }
        if (weaponOnly.get()) {
            String id = ItemUtil.id(held);
            if (!(id.endsWith("_sword") || id.endsWith("_axe"))) {
                return -1;
            }
        }
        int slot = InventoryManager.findHotbarSlot(self,
                s -> ItemUtil.isMace(s) && ItemUtil.enchantLevel(s, "breach") > 0);
        if (slot == -1 && !onlyBreach.get()) {
            slot = InventoryManager.findHotbarSlot(self, ItemUtil::isMace);
        }
        return slot;
    }

    /**
     * Called at the start of every attack. Returns true when it already performed the
     * swapped attack itself (the caller then skips the original one); false leaves the
     * original attack alone, including when the slot change is refused this tick.
     */
    public boolean beforeAttack(MinecraftClient client, Entity target) {
        ClientPlayerEntity self = client.player;
        if (!isEnabled() || self == null || SwapManager.inSwap() || client.interactionManager == null) {
            return false;
        }
        int slot = maceSlotFor(self, target);
        if (slot == -1) {
            return false;
        }
        int back = showSwap.get() ? backTicks.get().intValue() : 0;
        boolean swapped = SwapManager.withSwap(client, slot, back, () -> {
            // Re-entrant call: SwapManager.inSwap() is true, so this hook lets it through,
            // and vanilla's syncSelectedSlot() inside it has nothing left to send.
            client.interactionManager.attackEntity(self, target);
            return true;
        });
        if (swapped) {
            swaps++;
        }
        return swapped;
    }

    @Override
    public String hudLabel() {
        return name + " · " + swaps;
    }
}
