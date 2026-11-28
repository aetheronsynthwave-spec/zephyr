package dev.zephyr.modules.donut.scan;

/** A block position. Free of game classes. */
public record Pos(int x, int y, int z) {

    public static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    public static int keyX(long key) {
        return (int) key;
    }

    public static int keyZ(long key) {
        return (int) (key >> 32);
    }

    public long chunkKey() {
        return chunkKey(x >> 4, z >> 4);
    }

    public double distanceSq(Pos other) {
        double dx = x - (double) other.x;
        double dy = y - (double) other.y;
        double dz = z - (double) other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Distance squared ignoring height: what "how far from me" means on the surface. */
    public double flatSq(Pos other) {
        double dx = x - (double) other.x;
        double dz = z - (double) other.z;
        return dx * dx + dz * dz;
    }
}
