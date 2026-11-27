package dev.zephyr.core.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Version-tolerant item classification. New-content items (mace, spear) and enchantments
 * (density, breach, lunge) are matched by registry path so 1.21.x mapping drift across
 * point releases cannot break the combat modules.
 */
public final class ItemUtil {

    private ItemUtil() {
    }

    public static String id(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        Identifier ident = Registries.ITEM.getId(stack.getItem());
        return ident.getNamespace() + ":" + ident.getPath();
    }

    public static boolean isMace(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).getPath().contains("mace");
    }

    public static boolean isSpear(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).getPath().contains("spear");
    }

    public static boolean isTotem(ItemStack stack) {
        return stack.isOf(Items.TOTEM_OF_UNDYING);
    }

    public static boolean isXpBottle(ItemStack stack) {
        return stack.isOf(Items.EXPERIENCE_BOTTLE);
    }

    public static boolean isPearl(ItemStack stack) {
        return stack.isOf(Items.ENDER_PEARL);
    }

    public static boolean isEndCrystal(ItemStack stack) {
        return stack.isOf(Items.END_CRYSTAL);
    }

    public static boolean isGlowstone(ItemStack stack) {
        return stack.isOf(Items.GLOWSTONE);
    }

    public static boolean isRespawnAnchor(ItemStack stack) {
        return stack.isOf(Items.RESPAWN_ANCHOR);
    }

    public static boolean isObsidian(ItemStack stack) {
        return stack.isOf(Items.OBSIDIAN);
    }

    /**
     * Enchantment level by registry path, e.g. "density", "breach", "lunge", "wind_burst".
     * Reads through EnchantmentHelper (version-tolerant, no direct enchant enum refs).
     */
    public static int enchantLevel(ItemStack stack, String path) {
        if (stack.isEmpty()) {
            return 0;
        }
        ItemEnchantmentsComponent ench = net.minecraft.enchantment.EnchantmentHelper.getEnchantments(stack);
        for (net.minecraft.registry.entry.RegistryEntry<net.minecraft.enchantment.Enchantment> entry
                : ench.getEnchantments()) {
            var key = entry.getKey();
            if (key.isPresent() && key.get().getValue().getPath().equals(path)) {
                return ench.getLevel(entry);
            }
        }
        return 0;
    }

    /** Breach-enchanted mace detection for the swap module. */
    public static boolean isBreachMace(ItemStack stack, int minLevel) {
        return isMace(stack) && enchantLevel(stack, "breach") >= minLevel;
    }

    /** Attack cooldown progress (0..1) of the player for the current weapon. */
    public static float attackCooldownProgress(net.minecraft.entity.player.PlayerEntity player) {
        return player.getAttackCooldownProgress(0.0f);
    }

    /** @return true when the item is on cooldown. */
    public static boolean coolingDown(net.minecraft.entity.player.PlayerEntity player, ItemStack stack) {
        return player.getItemCooldownManager().isCoolingDown(stack.getItem());
    }
}
