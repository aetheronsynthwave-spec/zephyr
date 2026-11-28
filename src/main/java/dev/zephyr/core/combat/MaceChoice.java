package dev.zephyr.core.combat;

import dev.zephyr.core.util.ItemUtil;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/** Which hotbar mace (and axe) to use for a hit. */
public final class MaceChoice {

    private MaceChoice() {
    }

    /** Vanilla mace smash bonus: 4 per block up to 3, 2 per block up to 8, 1 per block beyond. */
    public static double smashBonus(double fall) {
        if (fall <= 1.5) {
            return 0.0;
        }
        return fall <= 3.0 ? 4.0 * fall : fall <= 8.0 ? 12.0 + 2.0 * (fall - 3.0) : 22.0 + (fall - 8.0);
    }

    /**
     * Best hotbar mace for this hit, or -1. Score = base 6 + smash bonus + density (0.5 per block
     * per level), reduced by the share the target's armour absorbs (4 % a point, at most 80 %)
     * except for what Breach pierces (15 % a level, counted from {@code breachFromArmor}), plus a
     * flat bonus for Wind Burst, whose bounce keeps the chain going.
     */
    public static int bestSlot(ClientPlayerEntity self, double fall, int armor, double breachFromArmor,
                               boolean preferWindBurst) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = self.getInventory().getStack(i);
            if (!ItemUtil.isMace(stack)) {
                continue;
            }
            int density = ItemUtil.enchantLevel(stack, "density");
            int breach = armor >= breachFromArmor ? ItemUtil.enchantLevel(stack, "breach") : 0;
            int wind = ItemUtil.enchantLevel(stack, "wind_burst");
            double base = 6.0 + smashBonus(fall) + 0.5 * density * Math.max(0.0, fall);
            double absorbed = Math.min(0.8, armor * 0.04) * (1.0 - Math.min(1.0, 0.15 * breach));
            double score = base * (1.0 - absorbed) + (wind > 0 && preferWindBurst ? 3.0 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** First hotbar axe (what disables a shield), or -1. */
    public static int axeSlot(ClientPlayerEntity self) {
        for (int i = 0; i < 9; i++) {
            if (ItemUtil.id(self.getInventory().getStack(i)).endsWith("_axe")) {
                return i;
            }
        }
        return -1;
    }
}
