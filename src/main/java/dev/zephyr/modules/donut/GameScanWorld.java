package dev.zephyr.modules.donut;

import dev.zephyr.modules.donut.scan.Mat;
import dev.zephyr.modules.donut.scan.ScanWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The client's loaded world as {@link ScanWorld}. Make one per tick and drop it: it caches
 * the last chunk it was asked for. Client thread only.
 */
final class GameScanWorld implements ScanWorld {

    private static final Map<Block, Mat> KINDS = new IdentityHashMap<>();

    private final ClientWorld world;
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private WorldChunk last;
    private int lastX;
    private int lastZ;
    private boolean cached;

    GameScanWorld(ClientWorld world) {
        this.world = world;
    }

    private WorldChunk chunk(int chunkX, int chunkZ) {
        if (!cached || chunkX != lastX || chunkZ != lastZ) {
            last = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
            lastX = chunkX;
            lastZ = chunkZ;
            cached = true;
        }
        return last;
    }

    private ChunkSection section(int chunkX, int sectionY, int chunkZ) {
        WorldChunk chunk = chunk(chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }
        ChunkSection[] sections = chunk.getSectionArray();
        int index = sectionY - chunk.sectionIndexToCoord(0);
        return index >= 0 && index < sections.length ? sections[index] : null;
    }

    private BlockState state(int x, int y, int z) {
        ChunkSection section = section(x >> 4, y >> 4, z >> 4);
        return section == null ? null : section.getBlockState(x & 15, y & 15, z & 15);
    }

    private static Mat kind(BlockState state) {
        Block block = state.getBlock();
        Mat known = KINDS.get(block);
        if (known != null) {
            return known;
        }
        String id = Registries.BLOCK.getId(block).getPath();
        if (id.equals("bee_nest")) {
            // Depends on the state, so never memoised.
            String text = state.toString();
            return Mat.of(id, text.contains("honey_level=") && !text.contains("honey_level=0"));
        }
        Mat mat = Mat.of(id, false);
        KINDS.put(block, mat);
        return mat;
    }

    @Override
    public boolean loaded(int chunkX, int chunkZ) {
        return chunk(chunkX, chunkZ) != null;
    }

    @Override
    public int bottomY() {
        return world.getBottomY();
    }

    @Override
    public int topY() {
        return world.getTopYInclusive();
    }

    @Override
    public long palette(int chunkX, int sectionY, int chunkZ) {
        ChunkSection section = section(chunkX, sectionY, chunkZ);
        if (section == null) {
            return 0;
        }
        long[] listed = new long[1];
        // Never matching walks every palette entry; a palette with no entries to walk
        // (raw ids) answers true, which is how it is told apart.
        boolean unreadable = section.hasAny(s -> {
            listed[0] |= kind(s).bit();
            return false;
        });
        return unreadable ? UNKNOWN : listed[0];
    }

    @Override
    public Mat block(int x, int y, int z) {
        BlockState state = state(x, y, z);
        return state == null ? Mat.AIR : kind(state);
    }

    @Override
    public boolean emits(int x, int y, int z) {
        BlockState state = state(x, y, z);
        return state != null && state.getLuminance() > 0;
    }

    @Override
    public boolean budOnBudding(int x, int y, int z) {
        BlockState state = state(x, y, z);
        if (state == null) {
            return false;
        }
        String text = state.toString();
        int at = text.indexOf("facing=");
        if (at >= 0) {
            String facing = text.substring(at + 7);
            // The bud grows from the block behind it.
            if (facing.startsWith("up")) {
                return block(x, y - 1, z) == Mat.BUDDING;
            }
            if (facing.startsWith("down")) {
                return block(x, y + 1, z) == Mat.BUDDING;
            }
            if (facing.startsWith("north")) {
                return block(x, y, z + 1) == Mat.BUDDING;
            }
            if (facing.startsWith("south")) {
                return block(x, y, z - 1) == Mat.BUDDING;
            }
            if (facing.startsWith("east")) {
                return block(x - 1, y, z) == Mat.BUDDING;
            }
            if (facing.startsWith("west")) {
                return block(x + 1, y, z) == Mat.BUDDING;
            }
        }
        return block(x + 1, y, z) == Mat.BUDDING || block(x - 1, y, z) == Mat.BUDDING
                || block(x, y + 1, z) == Mat.BUDDING || block(x, y - 1, z) == Mat.BUDDING
                || block(x, y, z + 1) == Mat.BUDDING || block(x, y, z - 1) == Mat.BUDDING;
    }

    @Override
    public int blockLight(int x, int y, int z) {
        return world.getLightLevel(LightType.BLOCK, cursor.set(x, y, z));
    }

    @Override
    public int skyLight(int x, int y, int z) {
        return world.getLightLevel(LightType.SKY, cursor.set(x, y, z));
    }
}
