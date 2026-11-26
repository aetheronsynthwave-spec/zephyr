package dev.zephyr.modules.utility;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.combat.SwapManager;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * AutoTool - while you hold attack on a block, selects the hotbar item that mines it
 * fastest, and (optionally) returns to your previous slot once you stop mining.
 *
 * <p>Runs in the pre-movement stage and selects through {@link SwapManager}, so it follows
 * the same rules as every other slot change: valid 0..8 index only, no packet when the
 * slot is already selected, at most one slot change per tick, never mid-swap of another
 * module. Vanilla restarts the break when the held stack changes, exactly as when a player
 * switches by hand.
 */
public final class AutoTool extends Module {

    private final BooleanSetting swapBack = bool("swapBack", "Return to previous slot", true);
    private final NumberSetting backDelay = num("backDelay", "Return delay (ticks)", 0, 40, 1, 6);
    private final BooleanSetting saveTools = bool("saveTools", "Skip nearly broken tools", true);
    private final NumberSetting minUses = num("minUses", "Min uses left", 1, 50, 1, 5);

    private int previousSlot = -1;
    private int idleTicks;

    public AutoTool() {
        super("AutoTool", ModuleCategory.UTILITY, 0);
    }

    @Override
    protected void onDisable() {
        previousSlot = -1;
    }

    @Override
    public void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.interactionManager == null) {
            return;
        }
        boolean mining = client.currentScreen == null
                && client.options.attackKey.isPressed()
                && !p.isCreative() && !p.isSpectator()
                && client.crosshairTarget != null
                && client.crosshairTarget.getType() == HitResult.Type.BLOCK;
        if (!mining) {
            if (previousSlot != -1 && swapBack.get() && ++idleTicks > backDelay.get()) {
                if (p.getInventory().getSelectedSlot() == previousSlot
                        || SwapManager.select(client, previousSlot)) {
                    previousSlot = -1; // refused this tick -> retried next tick
                }
            }
            return;
        }
        idleTicks = 0;
        if (SwapManager.pending() || SwapManager.inSwap()) {
            return; // another module owns the hotbar right now
        }
        BlockState state = client.world.getBlockState(((BlockHitResult) client.crosshairTarget).getBlockPos());
        if (state.isAir() || state.getHardness(client.world, ((BlockHitResult) client.crosshairTarget).getBlockPos()) < 0) {
            return; // nothing to mine / unbreakable
        }
        int current = p.getInventory().getSelectedSlot();
        int best = current;
        float bestScore = score(p.getInventory().getStack(current), state);
        for (int i = 0; i < 9; i++) {
            float sc = score(p.getInventory().getStack(i), state);
            if (sc > bestScore + 0.01f) {
                bestScore = sc;
                best = i;
            }
        }
        if (best != current && SwapManager.select(client, best)) {
            if (previousSlot == -1) {
                previousSlot = current;
            }
        }
    }

    /** Mining speed for the stack, with tools that can't harvest drops (or are about to break) ranked out. */
    private float score(ItemStack stack, BlockState state) {
        if (saveTools.get() && stack.isDamageable()
                && stack.getMaxDamage() - stack.getDamage() <= minUses.get()) {
            return -1f;
        }
        float speed = stack.getMiningSpeedMultiplier(state);
        if (state.isToolRequired() && !stack.isSuitableFor(state)) {
            speed *= 0.3f; // vanilla breaks 3.3x slower without the right tool, and drops nothing
        }
        return speed;
    }
}
