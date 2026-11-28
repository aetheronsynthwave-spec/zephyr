package dev.zephyr.modules.survival;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * DurabilityAlert - warns before gear breaks. Watches main hand, offhand and the four
 * armour slots; when a damageable item drops to the warning threshold it shows an
 * action-bar message (and optionally a pling), and warns again at the critical threshold.
 * Each slot re-arms once the item is repaired above the threshold or a different item is
 * put in the slot. Client-only: reads local item state, sends nothing.
 */
public final class DurabilityAlert extends Module {

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final NumberSetting warnPercent = num("warn", "Warn at % left", 1, 50, 1, 10);
    private final NumberSetting criticalPercent = num("critical", "Critical at % left", 1, 25, 1, 3);
    private final BooleanSetting sound = bool("sound", "Play sound", true);
    private final BooleanSetting armor = bool("armor", "Check armour", true);

    /** Per slot: 0 = armed, 1 = warned, 2 = critical warned. */
    private final int[] level = new int[SLOTS.length];
    /** Per slot: item identity the level belongs to (re-arm on swap). */
    private final ItemStack[] tracked = new ItemStack[SLOTS.length];

    public DurabilityAlert() {
        super("DurabilityAlert", ModuleCategory.SURVIVAL, 0);
    }

    @Override
    protected void onEnable() {
        java.util.Arrays.fill(level, 0);
        java.util.Arrays.fill(tracked, null);
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null) {
            return;
        }
        for (int i = 0; i < SLOTS.length; i++) {
            EquipmentSlot slot = SLOTS[i];
            if (!armor.get() && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            ItemStack stack = p.getEquippedStack(slot);
            if (tracked[i] == null || !ItemStack.areItemsEqual(tracked[i], stack)) {
                tracked[i] = stack.copy();
                level[i] = 0;
            }
            if (stack.isEmpty() || !stack.isDamageable() || stack.getMaxDamage() <= 0) {
                continue;
            }
            int left = stack.getMaxDamage() - stack.getDamage();
            double pct = 100.0 * left / stack.getMaxDamage();
            if (pct > warnPercent.get()) {
                level[i] = 0; // repaired (or never low): re-arm
                continue;
            }
            int wanted = pct <= criticalPercent.get() ? 2 : 1;
            if (wanted > level[i]) {
                level[i] = wanted;
                alert(client, stack, left, wanted == 2);
            }
        }
    }

    private void alert(MinecraftClient client, ItemStack stack, int left, boolean critical) {
        Text msg = Text.literal((critical ? "CRITICAL: " : "Low durability: "))
                .formatted(critical ? Formatting.WHITE : Formatting.GRAY)
                .append(stack.getName().copy().formatted(Formatting.WHITE))
                .append(Text.literal(" - " + left + " uses left").formatted(Formatting.GRAY));
        client.inGameHud.setOverlayMessage(msg, false);
        if (sound.get() && client.player != null) {
            client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, critical ? 0.6f : 1.2f);
        }
    }
}
