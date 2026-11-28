package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;

import java.util.HashSet;
import java.util.Set;

/**
 * XRay - only the chosen blocks are drawn, so ores show through the ground, fully lit.
 *
 * <p>Chunk meshes are built off the render thread, so they read an immutable set published
 * here; any change to the module or its settings rebuilds the world. Hooks: MixinBlock (which
 * faces are drawn), MixinAbstractBlockState (which blocks are drawn), MixinFluidRenderer and the
 * lightmap mixin (full brightness).
 */
public final class XRay extends Module {

    /** The blocks to draw, or null while X-ray is off. */
    private static volatile Set<Block> shown;

    private final BooleanSetting diamonds = bool("diamonds", "Diamond", true);
    private final BooleanSetting netherite = bool("netherite", "Ancient debris", true);
    private final BooleanSetting emerald = bool("emerald", "Emerald", true);
    private final BooleanSetting gold = bool("gold", "Gold", true);
    private final BooleanSetting iron = bool("iron", "Iron", true);
    private final BooleanSetting redstone = bool("redstone", "Redstone", false);
    private final BooleanSetting lapis = bool("lapis", "Lapis", false);
    private final BooleanSetting copper = bool("copper", "Copper", false);
    private final BooleanSetting coal = bool("coal", "Coal", false);
    private final BooleanSetting storage = bool("storage", "Chests, shulkers, spawners", true);
    private final BooleanSetting lava = bool("lava", "Lava and water", false);

    private static volatile boolean fluids;

    public XRay() {
        super("XRay", ModuleCategory.RENDER, 0);
    }

    public static boolean active() {
        return shown != null;
    }

    public static boolean visible(BlockState state) {
        Set<Block> set = shown;
        return set != null && set.contains(state.getBlock());
    }

    public static boolean showFluids() {
        return fluids;
    }

    @Override
    protected void onEnable() {
        apply(chosen());
    }

    @Override
    protected void onDisable() {
        apply(null);
    }

    /** A changed tick box takes effect on the next game tick. */
    @Override
    public void onTick(MinecraftClient client) {
        Set<Block> now = chosen();
        if (!now.equals(shown) || fluids != lava.get()) {
            apply(now);
        }
    }

    private void apply(Set<Block> set) {
        fluids = set != null && lava.get();
        shown = set == null ? null : Set.copyOf(set);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.worldRenderer != null && client.world != null) {
            // Section culling assumes solid blocks hide what is behind them, which is no longer true.
            client.chunkCullingEnabled = set == null;
            client.worldRenderer.reload();
        }
        if (client.gameRenderer != null) {
            client.gameRenderer.getLightmapTextureManager().tick();
        }
    }

    private Set<Block> chosen() {
        Set<Block> set = new HashSet<>();
        add(set, diamonds, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DIAMOND_BLOCK);
        add(set, netherite, Blocks.ANCIENT_DEBRIS, Blocks.NETHERITE_BLOCK);
        add(set, emerald, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.EMERALD_BLOCK);
        add(set, gold, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE, Blocks.GOLD_BLOCK,
                Blocks.RAW_GOLD_BLOCK);
        add(set, iron, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.IRON_BLOCK, Blocks.RAW_IRON_BLOCK);
        add(set, redstone, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        add(set, lapis, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        add(set, copper, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        add(set, coal, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        add(set, storage, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL, Blocks.SPAWNER,
                Blocks.SHULKER_BOX, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.HOPPER);
        return set;
    }

    private static void add(Set<Block> set, BooleanSetting on, Block... blocks) {
        if (on.get()) {
            set.addAll(java.util.Arrays.asList(blocks));
        }
    }
}
