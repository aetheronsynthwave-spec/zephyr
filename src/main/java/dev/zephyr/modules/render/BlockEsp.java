package dev.zephyr.modules.render;

import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * BlockESP - outlines selected block families inside already-loaded chunks. Presets cover
 * what Donut Client's BlockESP / Amethyst scanner highlighted: ores, ancient debris,
 * amethyst geodes and spawners. The scan is budgeted per tick (no frame hitches) and
 * the draw path reuses the shared line-box helper.
 */
public final class BlockEsp extends Module implements WorldRenderParticipant {

    private final ModeSetting preset = s(new ModeSetting("preset", "Preset",
            List.of("DIAMOND", "ORES", "DEBRIS", "AMETHYST", "SPAWNERS"), "ORES"));
    private final NumberSetting radius = num("radius", "Radius", 8, 48, 1, 24);
    private final NumberSetting budget = num("budget", "Blocks scanned / tick", 2000, 60000, 1000, 20000);
    private final NumberSetting maxBoxes = num("maxBoxes", "Max outlines", 16, 1024, 16, 256);

    private static final float[] C_DIAMOND = {0.3f, 0.9f, 1f};
    private static final float[] C_DEBRIS = {0.7f, 0.4f, 0.2f};
    private static final float[] C_AMETHYST = {0.75f, 0.4f, 1f};
    private static final float[] C_SPAWNERS = {1f, 0.3f, 0.3f};
    private static final float[] C_DEFAULT = {1f, 0.85f, 0.2f};

    private final List<BlockPos> found = new ArrayList<>();
    private List<BlockPos> building = new ArrayList<>();
    private int cx, cy, cz;       // scan cursor (offset from origin)
    private BlockPos origin = BlockPos.ORIGIN;
    private boolean scanning;
    public int lastCount;

    public BlockEsp() {
        super("BlockESP", ModuleCategory.RENDER, 0);
    }

    @Override
    protected void onEnable() {
        scanning = false;
        found.clear();
    }

    private boolean matches(BlockState st) {
        if (st.isAir()) {
            return false;
        }
        String id = Registries.BLOCK.getId(st.getBlock()).getPath();
        return switch (preset.get()) {
            case "DIAMOND" -> id.equals("diamond_ore") || id.equals("deepslate_diamond_ore");
            case "DEBRIS" -> id.equals("ancient_debris");
            case "AMETHYST" -> id.contains("amethyst_cluster") || id.equals("budding_amethyst");
            case "SPAWNERS" -> id.equals("spawner") || id.equals("trial_spawner");
            default -> id.endsWith("_ore") || id.equals("ancient_debris");
        };
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            return;
        }
        int r = radius.asInt();
        if (!scanning) {
            origin = mc.player.getBlockPos();
            building = new ArrayList<>();
            cx = cy = cz = -r;
            scanning = true;
        }
        int left = budget.asInt();
        BlockPos.Mutable m = new BlockPos.Mutable();
        int minY = mc.world.getBottomY(), maxY = mc.world.getTopYInclusive();
        while (left > 0 && scanning) {
            int y = origin.getY() + cy;
            if (y >= minY && y <= maxY) {
                m.set(origin.getX() + cx, y, origin.getZ() + cz);
                if (mc.world.isChunkLoaded(m) && matches(mc.world.getBlockState(m))) {
                    if (building.size() < 4096) {
                        building.add(m.toImmutable());
                    }
                }
            }
            left--;
            if (++cx > r) {
                cx = -r;
                if (++cz > r) {
                    cz = -r;
                    if (++cy > r) {
                        found.clear();
                        BlockPos c = mc.player.getBlockPos();
                        building.sort((a, b) -> Double.compare(a.getSquaredDistance(c), b.getSquaredDistance(c)));
                        found.addAll(building);
                        lastCount = found.size();
                        scanning = false;
                    }
                }
            }
        }
    }

    @Override
    public void onWorldRender(WorldRenderContext ctx) {
        if (ctx.consumers() == null || found.isEmpty()) {
            return;
        }
        float[] c = switch (preset.get()) {
            case "DIAMOND" -> C_DIAMOND;
            case "DEBRIS" -> C_DEBRIS;
            case "AMETHYST" -> C_AMETHYST;
            case "SPAWNERS" -> C_SPAWNERS;
            default -> C_DEFAULT;
        };
        int n = 0, max = maxBoxes.asInt();
        for (BlockPos p : found) {
            if (n++ >= max) {
                break;
            }
            int x = p.getX(), y = p.getY(), z = p.getZ();
            RenderUtil.drawBoxOutline(ctx.matrices(), ctx.consumers(), x, y, z, x + 1, y + 1, z + 1,
                    c[0], c[1], c[2], 1f, false);
        }
    }

    @Override
    public String hudLabel() {
        return name + " · " + preset.get() + " · " + lastCount;
    }
}
