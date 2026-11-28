package dev.zephyr.modules.donut.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * A plain-text account of what the client actually holds around the player, one line per
 * 16-block height band. Run it on the surface and again underground and compare: what only
 * appears the second time is what the server withholds until you are close.
 */
public final class ProbeReport {

    private ProbeReport() {
    }

    public static String build(ScanWorld world, int chunkX, int chunkZ, int radius, List<String> header) {
        StringBuilder out = new StringBuilder();
        header.forEach(line -> out.append(line).append('\n'));
        List<int[]> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (world.loaded(chunkX + dx, chunkZ + dz)) {
                    chunks.add(new int[]{chunkX + dx, chunkZ + dz});
                }
            }
        }
        out.append("loaded chunks within ").append(radius).append(": ").append(chunks.size()).append('\n');
        Mat[] mats = Mat.values();
        for (int sy = Math.floorDiv(world.bottomY(), 16); sy <= Math.floorDiv(world.topY(), 16); sy++) {
            long[] blocks = new long[mats.length];
            long listedOnly = 0;
            int unknownPalettes = 0;
            int maxLight = 0;
            long lit = 0;
            for (int[] chunk : chunks) {
                long listed = world.palette(chunk[0], sy, chunk[1]);
                long present = 0;
                for (int y = sy << 4; y < (sy << 4) + 16; y++) {
                    for (int z = chunk[1] << 4; z < (chunk[1] << 4) + 16; z++) {
                        for (int x = chunk[0] << 4; x < (chunk[0] << 4) + 16; x++) {
                            Mat mat = world.block(x, y, z);
                            blocks[mat.ordinal()]++;
                            present |= mat.bit();
                            int light = world.blockLight(x, y, z);
                            maxLight = Math.max(maxLight, light);
                            if (light > 0) {
                                lit++;
                            }
                        }
                    }
                }
                if (listed == ScanWorld.UNKNOWN) {
                    unknownPalettes++;
                } else {
                    listedOnly |= listed & ~present;
                }
            }
            out.append("Y ").append(sy << 4).append("..").append((sy << 4) + 15);
            for (Mat mat : mats) {
                if (blocks[mat.ordinal()] > 0) {
                    out.append(' ').append(mat).append('=').append(blocks[mat.ordinal()]);
                }
            }
            out.append(" | maxBlockLight=").append(maxLight).append(" litCells=").append(lit);
            out.append(" unreadablePalettes=").append(unknownPalettes);
            out.append(" listedButNotShown=");
            boolean any = false;
            for (Mat mat : mats) {
                if ((listedOnly & mat.bit()) != 0 && (mat.tracked() || mat.geode())) {
                    out.append(any ? "," : "").append(mat);
                    any = true;
                }
            }
            out.append(any ? "" : "none").append('\n');
        }
        return out.toString();
    }
}
