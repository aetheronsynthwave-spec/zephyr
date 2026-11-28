package dev.zephyr.modules.donut.scan;

/** The data the client holds, as the scanners see it. Coordinates are world block coordinates. */
public interface ScanWorld {

    /** Palette answer for a section that stores raw ids and so lists nothing. */
    long UNKNOWN = -1L;

    boolean loaded(int chunkX, int chunkZ);

    int bottomY();

    /** Highest block Y, inclusive. */
    int topY();

    /** {@link Mat#bit()}s listed in the section's block palette, {@link #UNKNOWN}, or 0. */
    long palette(int chunkX, int sectionY, int chunkZ);

    /** AIR where nothing is loaded. */
    Mat block(int x, int y, int z);

    /** Whether the block here gives off light itself. */
    boolean emits(int x, int y, int z);

    /** For a bud: whether the block it grows from is budding amethyst. */
    boolean budOnBudding(int x, int y, int z);

    int blockLight(int x, int y, int z);

    int skyLight(int x, int y, int z);
}
