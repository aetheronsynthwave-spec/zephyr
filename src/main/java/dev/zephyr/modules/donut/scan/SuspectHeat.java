// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre's "Sus Chunk Finder" by 4ldenz, as ported in Donut Client 4.0.
// See resources/licenses/spectre-esp-adaptation.txt.
package dev.zephyr.modules.donut.scan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Chunks where amethyst has had time to grow out suggest somebody keeps them loaded.
 * Each chunk's grown count warms the chunks around it, any young amethyst nearby rules the
 * area out, and only local peaks are reported. A clue, not proof of a base.
 */
public final class SuspectHeat {

    private static final int MIN_NEIGHBOURS = 3;

    private record Stats(int grown, int ungrown, boolean deepBlocks) {
    }

    /**
     * @param suspects chunk key to heat
     * @param ruledOut chunk keys with young amethyst within reach
     */
    public record Report(Map<Long, Integer> suspects, Set<Long> ruledOut) {
    }

    private final Map<Long, Stats> chunks = new HashMap<>();

    public void put(ChunkScan.Result result) {
        chunks.put(Pos.chunkKey(result.chunkX(), result.chunkZ()),
                new Stats(result.grown(), result.ungrown(), result.deepBlocks()));
    }

    public void remove(long key) {
        chunks.remove(key);
    }

    public void clear() {
        chunks.clear();
    }

    /** Chunks currently counted. */
    public Set<Long> scanned() {
        return Set.copyOf(chunks.keySet());
    }

    public Report compute(int reach, int sensitivity) {
        Map<Long, Integer> heat = new HashMap<>();
        Set<Long> ruledOut = new HashSet<>();
        chunks.forEach((key, stats) -> {
            if (stats.grown == 0 && stats.ungrown == 0) {
                return;
            }
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    long area = Pos.chunkKey(Pos.keyX(key) + dx, Pos.keyZ(key) + dz);
                    if (stats.grown > 0) {
                        heat.merge(area, stats.grown, Integer::sum);
                    }
                    if (stats.ungrown > 0) {
                        ruledOut.add(area);
                    }
                }
            }
        });
        Map<Long, Integer> eligible = new HashMap<>();
        heat.forEach((key, value) -> {
            if (!ruledOut.contains(key) && chunks.containsKey(key) && neighbours(key) >= MIN_NEIGHBOURS) {
                eligible.put(key, value);
            }
        });
        Map<Long, Integer> suspects = new HashMap<>();
        eligible.forEach((key, value) -> {
            // A chunk sent with nothing underground gives less to go on, so any heat counts.
            int needed = chunks.get(key).deepBlocks ? sensitivity : 1;
            if (value >= needed && peak(eligible, key, value, reach)) {
                suspects.put(key, value);
            }
        });
        return new Report(suspects, ruledOut);
    }

    private int neighbours(long key) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0)
                        && chunks.containsKey(Pos.chunkKey(Pos.keyX(key) + dx, Pos.keyZ(key) + dz))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean peak(Map<Long, Integer> field, long key, int value, int reach) {
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                Integer other = field.get(Pos.chunkKey(Pos.keyX(key) + dx, Pos.keyZ(key) + dz));
                if (other != null && other > value) {
                    return false;
                }
            }
        }
        return true;
    }
}
