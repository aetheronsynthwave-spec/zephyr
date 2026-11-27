package dev.zephyr.core;

import net.minecraft.client.MinecraftClient;

/**
 * Lightweight long-window profiler for the mod's own cost centers. Accumulates
 * nanoseconds in plain fields (zero allocation on the hot path) and reports
 * average per-tick / per-frame milliseconds plus a frames-per-second reading.
 *
 * <p>Enable with /zephyr profile (toggles); the report prints every 10 seconds in
 * chat. Typical healthy numbers on a 250-fps rig: tick < 0.4 ms, world render
 * < 0.2 ms, hud < 0.05 ms.
 */
public final class ZephyrProfiler {

    private ZephyrProfiler() {
    }

    private static boolean enabled;

    // Accumulators (reset each report window).
    private static long tickNs;
    private static int tickCount;
    private static long worldNs;
    private static int frameCount;
    private static long hudNs;
    private static long windowStart = System.currentTimeMillis();

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle() {
        enabled = !enabled;
        reset();
    }

    private static void reset() {
        tickNs = worldNs = hudNs = 0;
        tickCount = frameCount = 0;
        windowStart = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // Measurement points (called unconditionally; the branches are trivial)
    // ------------------------------------------------------------------

    public static void addTick(long ns) {
        if (enabled) {
            tickNs += ns;
            tickCount++;
        }
    }

    public static void addWorldRender(long ns) {
        if (enabled) {
            worldNs += ns;
            frameCount++;
        }
    }

    public static void addHudRender(long ns) {
        if (enabled) {
            hudNs += ns;
        }
    }

    /** Report every 10 s while enabled; returns null when there is nothing to report. */
    public static String report() {
        if (!enabled) {
            return null;
        }
        long windowMs = System.currentTimeMillis() - windowStart;
        if (windowMs < 10_000 || tickCount == 0 || frameCount == 0) {
            return null;
        }
        double fps = frameCount * 1000.0 / windowMs;
        String text = String.format(
                "[Zephyr] %.0f fps | tick %.2f ms avg (%d) | world-render %.2f ms avg (%d frames) | hud %.2f ms avg",
                fps,
                tickNs / 1e6 / tickCount, tickCount,
                worldNs / 1e6 / frameCount, frameCount,
                hudNs / 1e6 / frameCount);
        reset();
        return text;
    }

    /** Sends the pending report to chat when due (called once per client tick). */
    public static void chatReport(MinecraftClient client) {
        String r = report();
        if (r != null && client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal(r), false);
        }
    }
}
