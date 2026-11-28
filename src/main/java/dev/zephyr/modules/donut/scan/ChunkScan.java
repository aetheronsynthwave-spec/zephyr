// SPDX-License-Identifier: GPL-3.0-only
// The palette, bud-light, glow-edge, underground-light and growth-count methods are adapted
// from Spectre by 4ldenz, as ported in Donut Client 4.0 (github.com/Raindots/donut-client).
// See resources/licenses/spectre-esp-adaptation.txt.
package dev.zephyr.modules.donut.scan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything that can be read out of one loaded chunk. Only data already received is used:
 * a server that never sends something cannot be made to show it.
 */
public final class ChunkScan {

    /**
     * @param glowCells     cells needed before a chunk counts for the glow-edge method
     * @param lightMin      weakest underground block light that counts
     * @param lightCells    lit cells needed before a chunk counts as lit underground
     * @param denoise       drop faint light patches with no brighter source
     * @param skipLava      drop light patches that touch lava
     * @param paletteGrowth judge amethyst growth from palettes instead of single blocks
     * @param skipNatural   ignore light that traces back to a natural cave light
     */
    public record Params(int glowCells, int lightMin, int lightCells, boolean denoise, boolean skipLava,
                         boolean paletteGrowth, boolean skipNatural) {
        public Params(int glowCells, int lightMin, int lightCells, boolean denoise, boolean skipLava,
                      boolean paletteGrowth) {
            this(glowCells, lightMin, lightCells, denoise, skipLava, paletteGrowth, true);
        }

        public static Params defaults() {
            return new Params(12, 5, 8, true, true, false);
        }
    }

    public record Find(Pos pos, Mat mat) {
    }

    /**
     * @param grown      grown amethyst (growth method)
     * @param ungrown    amethyst still growing
     * @param deepBlocks anything but air between Y -16 and 30
     * @param checked    trace kinds this scan could judge; absent kinds say nothing
     */
    public record Result(int chunkX, int chunkZ, List<Find> finds, List<Trace> traces, int grown,
                         int ungrown, boolean deepBlocks, Set<Trace.Kind> checked) {
    }

    private static final int MAX_FINDS = 2048;
    private static final int GLOW_MIN_Y = -58;
    private static final int GLOW_MAX_Y = 30;
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final long WANTED;
    private static final long STORAGE;
    private static final long BUDS;
    private static final long GEODE;

    static {
        long wanted = 0;
        long storage = 0;
        long buds = 0;
        long geode = 0;
        for (Mat mat : Mat.values()) {
            if (mat.tracked() || mat.geode()) {
                wanted |= mat.bit();
            }
            if (mat.storage()) {
                storage |= mat.bit();
            }
            if (mat.bud()) {
                buds |= mat.bit();
            }
            if (mat.geode()) {
                geode |= mat.bit();
            }
        }
        WANTED = wanted;
        STORAGE = storage;
        BUDS = buds;
        GEODE = geode;
    }

    private ChunkScan() {
    }

    public static Result scan(ScanWorld world, int chunkX, int chunkZ, Params params) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        List<Find> finds = new ArrayList<>();
        List<Trace> traces = new ArrayList<>();
        EnumSet<Trace.Kind> checked = EnumSet.of(Trace.Kind.PALETTE_AMETHYST, Trace.Kind.PALETTE_STORAGE,
                Trace.Kind.BEES);
        List<Integer> geodeSections = new ArrayList<>();
        int grown = 0;
        int ungrown = 0;
        boolean paletteBud = false;
        boolean paletteCluster = false;
        long hiddenStorage = 0;
        Pos hiddenStorageAt = null;
        int hiddenStorageSections = 0;
        Pos hiddenAmethystAt = null;
        int hiddenAmethystSections = 0;
        List<Pos> bees = new ArrayList<>();

        int minSection = Math.floorDiv(world.bottomY(), 16);
        int maxSection = Math.floorDiv(world.topY(), 16);
        for (int sy = minSection; sy <= maxSection; sy++) {
            long listed = world.palette(chunkX, sy, chunkZ);
            boolean known = listed != ScanWorld.UNKNOWN;
            if (known && (listed & WANTED) == 0) {
                continue;
            }
            long present = 0;
            int baseY = sy << 4;
            for (int y = baseY; y < baseY + 16; y++) {
                for (int z = baseZ; z < baseZ + 16; z++) {
                    for (int x = baseX; x < baseX + 16; x++) {
                        Mat mat = world.block(x, y, z);
                        if ((mat.bit() & WANTED) == 0) {
                            continue;
                        }
                        present |= mat.bit();
                        if (mat.tracked() && finds.size() < MAX_FINDS) {
                            finds.add(new Find(new Pos(x, y, z), mat));
                        }
                        if (mat.bees()) {
                            bees.add(new Pos(x, y, z));
                        }
                        if (!params.paletteGrowth || !known) {
                            if (mat == Mat.CLUSTER) {
                                grown++;
                            } else if (mat.bud()) {
                                if (world.budOnBudding(x, y, z)) {
                                    ungrown++;
                                } else {
                                    grown++;
                                }
                            }
                        }
                    }
                }
            }
            if ((present & GEODE) != 0) {
                geodeSections.add(sy);
            }
            if (!known) {
                continue;
            }
            paletteBud |= (listed & BUDS) != 0;
            paletteCluster |= (listed & Mat.CLUSTER.bit()) != 0;
            Pos centre = new Pos(baseX + 8, baseY + 8, baseZ + 8);
            long storageGap = listed & STORAGE & ~present;
            if (storageGap != 0) {
                hiddenStorage |= storageGap;
                hiddenStorageSections++;
                if (hiddenStorageAt == null) {
                    hiddenStorageAt = centre;
                }
            }
            if ((listed & (BUDS | Mat.CLUSTER.bit()) & ~present) != 0) {
                hiddenAmethystSections++;
                if (hiddenAmethystAt == null) {
                    Pos beside = airBesideGeode(world, baseX, baseY, baseZ);
                    hiddenAmethystAt = beside != null ? beside : centre;
                }
            }
        }
        if (params.paletteGrowth) {
            if (paletteBud) {
                ungrown++;
            } else if (paletteCluster) {
                grown++;
            }
        }
        if (hiddenStorageAt != null) {
            traces.add(new Trace(Trace.Kind.PALETTE_STORAGE, hiddenStorageAt, hiddenStorageSections,
                    hiddenStorage, List.of()));
        }
        if (hiddenAmethystAt != null) {
            traces.add(Trace.at(Trace.Kind.PALETTE_AMETHYST, hiddenAmethystAt, hiddenAmethystSections));
        }
        if (!bees.isEmpty()) {
            traces.add(new Trace(Trace.Kind.BEES, bees.get(0), bees.size(), 0, bees));
        }

        // Light reaches across chunk borders, so these need the four neighbours loaded.
        if (world.loaded(chunkX + 1, chunkZ) && world.loaded(chunkX - 1, chunkZ)
                && world.loaded(chunkX, chunkZ + 1) && world.loaded(chunkX, chunkZ - 1)) {
            checked.add(Trace.Kind.BUD_LIGHT);
            checked.add(Trace.Kind.GLOW);
            checked.add(Trace.Kind.DEEP_LIGHT);
            budLight(world, baseX, baseZ, geodeSections, traces);
            glow(world, baseX, baseZ, params.glowCells, traces);
            deepLight(world, baseX, baseZ, params, traces);
        }
        return new Result(chunkX, chunkZ, finds, traces, grown, ungrown, deepBlocks(world, baseX, baseZ),
                checked);
    }

    private static boolean touchesGeode(ScanWorld world, int x, int y, int z) {
        for (int[] s : SIDES) {
            if (world.block(x + s[0], y + s[1], z + s[2]).geode()) {
                return true;
            }
        }
        return false;
    }

    private static Pos airBesideGeode(ScanWorld world, int baseX, int baseY, int baseZ) {
        for (int y = baseY; y < baseY + 16; y++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int x = baseX; x < baseX + 16; x++) {
                    if (world.block(x, y, z) == Mat.AIR && touchesGeode(world, x, y, z)) {
                        return new Pos(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    private static void budLight(ScanWorld world, int baseX, int baseZ, List<Integer> sections,
                                 List<Trace> out) {
        List<Pos> cells = new ArrayList<>();
        int count = 0;
        for (int sy : sections) {
            for (int y = sy << 4; y < (sy << 4) + 16; y++) {
                for (int z = baseZ; z < baseZ + 16; z++) {
                    for (int x = baseX; x < baseX + 16; x++) {
                        int light = world.blockLight(x, y, z);
                        if ((light == 1 || light == 2 || light == 4 || light == 5) && !world.emits(x, y, z)
                                && touchesGeode(world, x, y, z) && brightestHere(world, x, y, z, light)) {
                            count++;
                            if (cells.size() < Trace.MAX_CELLS) {
                                cells.add(new Pos(x, y, z));
                            }
                        }
                    }
                }
            }
        }
        if (count > 0) {
            out.add(new Trace(Trace.Kind.BUD_LIGHT, cells.get(0), count, 0, cells));
        }
    }

    private static boolean brightestHere(ScanWorld world, int x, int y, int z, int light) {
        for (int[] s : SIDES) {
            if (world.blockLight(x + s[0], y + s[1], z + s[2]) >= light) {
                return false;
            }
        }
        return true;
    }

    private static boolean open(Mat mat) {
        return mat == Mat.AIR || mat == Mat.CLUSTER;
    }

    private static void glow(ScanWorld world, int baseX, int baseZ, int needed, List<Trace> out) {
        List<Pos> cells = new ArrayList<>();
        int count = 0;
        int from = Math.max(GLOW_MIN_Y, world.bottomY());
        int to = Math.min(GLOW_MAX_Y, world.topY());
        for (int y = from; y <= to; y++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int x = baseX; x < baseX + 16; x++) {
                    if (open(world.block(x, y, z)) && world.blockLight(x, y, z) == 0 && glowEdge(world, x, y, z)) {
                        count++;
                        if (cells.size() < Trace.MAX_CELLS) {
                            cells.add(new Pos(x, y, z));
                        }
                    }
                }
            }
        }
        if (count > 0 && count >= needed) {
            out.add(new Trace(Trace.Kind.GLOW, cells.get(cells.size() / 2), count, 0, cells));
        }
    }

    /** A dark cell whose brightest neighbour is exactly 4, with an open neighbour at 4. */
    private static boolean glowEdge(ScanWorld world, int x, int y, int z) {
        boolean opening = false;
        for (int[] s : SIDES) {
            int light = world.blockLight(x + s[0], y + s[1], z + s[2]);
            if (light > 4) {
                return false;
            }
            if (light == 4 && !opening && open(world.block(x + s[0], y + s[1], z + s[2]))) {
                opening = true;
            }
        }
        return opening;
    }

    private static void deepLight(ScanWorld world, int baseX, int baseZ, Params params, List<Trace> out) {
        int bottom = world.bottomY();
        int height = Math.min(0, world.topY() + 1) - bottom;
        if (height <= 0) {
            return;
        }
        byte[] lit = new byte[height * 256];
        boolean any = false;
        Map<Pos, Boolean> sources = new HashMap<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int light = world.blockLight(baseX + x, bottom + y, baseZ + z);
                    if (light >= params.lightMin && light > world.skyLight(baseX + x, bottom + y, baseZ + z)
                            && !(params.skipNatural
                            && fromNature(world, new Pos(baseX + x, bottom + y, baseZ + z), sources))) {
                        lit[y * 256 + z * 16 + x] = (byte) light;
                        any = true;
                    }
                }
            }
        }
        if (!any) {
            return;
        }
        List<Pos> kept = new ArrayList<>();
        List<Integer> keptLight = new ArrayList<>();
        ArrayDeque<Integer> todo = new ArrayDeque<>();
        boolean[] seen = new boolean[lit.length];
        for (int start = 0; start < lit.length; start++) {
            if (lit[start] == 0 || seen[start]) {
                continue;
            }
            List<Integer> patch = new ArrayList<>();
            int peak = 0;
            int outside = 0;
            boolean lava = false;
            seen[start] = true;
            todo.add(start);
            while (!todo.isEmpty()) {
                int at = todo.removeFirst();
                patch.add(at);
                peak = Math.max(peak, lit[at]);
                int x = at & 15;
                int z = (at >> 4) & 15;
                int y = at >> 8;
                for (int[] s : SIDES) {
                    int nx = x + s[0];
                    int ny = y + s[1];
                    int nz = z + s[2];
                    if (params.skipLava && !lava && world.block(baseX + nx, bottom + ny, baseZ + nz) == Mat.LAVA) {
                        lava = true;
                    }
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                        int there = world.blockLight(baseX + nx, bottom + ny, baseZ + nz);
                        if (there > outside && !(params.skipNatural && there >= params.lightMin
                                && fromNature(world, new Pos(baseX + nx, bottom + ny, baseZ + nz), sources))) {
                            outside = there;
                        }
                        continue;
                    }
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    int next = ny * 256 + nz * 16 + nx;
                    if (lit[next] != 0 && !seen[next]) {
                        seen[next] = true;
                        todo.add(next);
                    }
                }
            }
            // Brighter across the border: the source is next door and that chunk reports it.
            if (outside > peak || lava || params.denoise && peak <= params.lightMin + 2) {
                continue;
            }
            for (int at : patch) {
                kept.add(new Pos(baseX + (at & 15), bottom + (at >> 8), baseZ + ((at >> 4) & 15)));
                keptLight.add((int) lit[at]);
            }
        }
        if (kept.isEmpty() || kept.size() < params.lightCells) {
            return;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> keptLight.get(i)).reversed());
        List<Pos> brightest = new ArrayList<>();
        for (int i = 0; i < Math.min(Trace.MAX_CELLS, order.size()); i++) {
            brightest.add(kept.get(order.get(i)));
        }
        out.add(new Trace(Trace.Kind.DEEP_LIGHT, brightest.get(0), kept.size(), 0, brightest));
    }

    /**
     * Follows the light uphill, always to the brightest neighbour, to the block it comes
     * from. A source the server did not send ends the walk on something that is no cave
     * light, so unexplained light stays reported. Crosses chunk borders.
     */
    private static boolean fromNature(ScanWorld world, Pos start, Map<Pos, Boolean> known) {
        List<Pos> path = new ArrayList<>();
        Pos at = start;
        Boolean natural = known.get(at);
        while (natural == null) {
            path.add(at);
            Pos up = null;
            int best = world.blockLight(at.x(), at.y(), at.z());
            for (int[] s : SIDES) {
                int light = world.blockLight(at.x() + s[0], at.y() + s[1], at.z() + s[2]);
                if (light > best) {
                    best = light;
                    up = new Pos(at.x() + s[0], at.y() + s[1], at.z() + s[2]);
                }
            }
            if (up == null) {
                natural = world.naturalLight(at.x(), at.y(), at.z()) >= best;
            } else {
                at = up;
                natural = known.get(at);
            }
        }
        for (Pos cell : path) {
            known.put(cell, natural);
        }
        return natural;
    }

    private static boolean deepBlocks(ScanWorld world, int baseX, int baseZ) {
        int from = Math.max(-16, world.bottomY());
        int to = Math.min(30, world.topY());
        for (int y = from; y <= to; y++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int x = baseX; x < baseX + 16; x++) {
                    if (world.block(x, y, z) != Mat.AIR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
