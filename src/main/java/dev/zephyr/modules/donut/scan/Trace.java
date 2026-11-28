package dev.zephyr.modules.donut.scan;

import java.util.List;

/**
 * A hint that something is in a chunk, weaker than seeing the block.
 *
 * @param strength cells, blocks or heat, depending on the kind
 * @param mats     {@link Mat#bit()}s involved, 0 where it does not apply
 * @param cells    sample positions to outline (bounded)
 */
public record Trace(Kind kind, Pos pos, int strength, long mats, List<Pos> cells) {

    public enum Kind {
        /** The section palette lists amethyst growth that no block shows. */
        PALETTE_AMETHYST,
        /** The section palette lists storage that no block shows. */
        PALETTE_STORAGE,
        /** Bud-strength block light peaking beside geode blocks. */
        BUD_LIGHT,
        /** Dark open cells bordering light level 4, which normal lighting cannot make. */
        GLOW,
        /** Block light underground that skylight does not explain. */
        DEEP_LIGHT,
        /** Amethyst all grown out here and nothing young nearby: loaded for a long time. */
        GROWTH,
        /** Honeyed nests or crafted hives. */
        BEES,
        SOUND_AMETHYST,
        SOUND_STORAGE
    }

    public static final int MAX_CELLS = 64;

    public Trace {
        cells = List.copyOf(cells.size() > MAX_CELLS ? cells.subList(0, MAX_CELLS) : cells);
    }

    public static Trace at(Kind kind, Pos pos, int strength) {
        return new Trace(kind, pos, strength, 0, List.of());
    }
}
