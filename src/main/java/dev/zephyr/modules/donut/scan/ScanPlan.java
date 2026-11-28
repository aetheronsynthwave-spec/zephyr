package dev.zephyr.modules.donut.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Which chunks to scan next: the ones around the player first (only close up can something
 * be ruled out), then chunks never scanned, then the longest unchecked.
 */
public final class ScanPlan {

    /**
     * @param order   chunk keys, first to be scanned first
     * @param covered at least nine in ten loaded chunks have been scanned once
     */
    public record Plan(List<Long> order, boolean covered) {
    }

    private ScanPlan() {
    }

    /**
     * @param scannedAt chunk key to the tick of its latest scan
     * @param rest      ticks before a scanned chunk is due again
     */
    public static Plan make(int chunkX, int chunkZ, int radius, BiPredicate<Integer, Integer> loaded,
                            Map<Long, Integer> scannedAt, int now, int rest) {
        List<long[]> chunks = new ArrayList<>();
        int total = 0;
        int fresh = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!loaded.test(chunkX + dx, chunkZ + dz)) {
                    continue;
                }
                total++;
                long key = Pos.chunkKey(chunkX + dx, chunkZ + dz);
                Integer when = scannedAt.get(key);
                if (when == null) {
                    fresh++;
                } else if (now - when < rest) {
                    continue;
                }
                boolean near = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                long rank = near ? 0 : when == null ? 1 : 2;
                chunks.add(new long[]{rank, rank == 2 ? when : 0, (long) dx * dx + (long) dz * dz, key});
            }
        }
        chunks.sort(Comparator.<long[]>comparingLong(c -> c[0]).thenComparingLong(c -> c[1])
                .thenComparingLong(c -> c[2]));
        List<Long> order = new ArrayList<>(chunks.size());
        chunks.forEach(c -> order.add(c[3]));
        return new Plan(order, total > 0 && fresh * 10 <= total);
    }
}
