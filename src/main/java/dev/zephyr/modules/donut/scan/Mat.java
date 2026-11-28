package dev.zephyr.modules.donut.scan;

/** What a block means to the scanners. Free of game classes. */
public enum Mat {
    AIR, OTHER, LAVA,
    CLUSTER, SMALL_BUD, MEDIUM_BUD, LARGE_BUD, BUDDING, AMETHYST_BLOCK,
    CHEST, BARREL, SHULKER, ENDER_CHEST, HOPPER, FURNACE, DROPPER, SPAWNER,
    HONEY_NEST, BEEHIVE;

    /** From a block id without the namespace; {@code honey} only matters for bee nests. */
    public static Mat of(String id, boolean honey) {
        if (id.endsWith("shulker_box")) {
            return SHULKER;
        }
        return switch (id) {
            case "air", "cave_air", "void_air" -> AIR;
            case "lava" -> LAVA;
            case "amethyst_cluster" -> CLUSTER;
            case "small_amethyst_bud" -> SMALL_BUD;
            case "medium_amethyst_bud" -> MEDIUM_BUD;
            case "large_amethyst_bud" -> LARGE_BUD;
            case "budding_amethyst" -> BUDDING;
            case "amethyst_block" -> AMETHYST_BLOCK;
            case "chest", "trapped_chest" -> CHEST;
            case "barrel" -> BARREL;
            case "ender_chest" -> ENDER_CHEST;
            case "hopper" -> HOPPER;
            case "furnace", "blast_furnace", "smoker" -> FURNACE;
            case "dropper", "dispenser", "crafter" -> DROPPER;
            case "spawner", "trial_spawner" -> SPAWNER;
            case "bee_nest" -> honey ? HONEY_NEST : OTHER;
            case "beehive" -> BEEHIVE;
            default -> OTHER;
        };
    }

    public long bit() {
        return 1L << ordinal();
    }

    public boolean bud() {
        return this == SMALL_BUD || this == MEDIUM_BUD || this == LARGE_BUD;
    }

    /** Amethyst worth outlining: clusters, buds and budding blocks. */
    public boolean amethyst() {
        return this == CLUSTER || this == BUDDING || bud();
    }

    /** Part of a geode, grown or not. */
    public boolean geode() {
        return this == BUDDING || this == AMETHYST_BLOCK;
    }

    public boolean storage() {
        return ordinal() >= CHEST.ordinal() && ordinal() <= SPAWNER.ordinal();
    }

    public boolean bees() {
        return this == HONEY_NEST || this == BEEHIVE;
    }

    /** Single blocks that are remembered by position. */
    public boolean tracked() {
        return storage() || amethyst() || bees();
    }
}
