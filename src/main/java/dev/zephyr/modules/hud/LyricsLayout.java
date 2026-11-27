package dev.zephyr.modules.hud;

/** Pure sizing and animation math for {@link SpotifyLyrics}. */
final class LyricsLayout {

    private LyricsLayout() {
    }

    /** Ease-out cubic progress, 0..1. A non-positive duration is already finished. */
    static float ease(long elapsedMs, long durationMs) {
        if (durationMs <= 0 || elapsedMs >= durationMs) {
            return 1f;
        }
        float q = 1f - Math.max(0L, elapsedMs) / (float) durationMs;
        return 1f - q * q * q;
    }

    static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * The scale nearest to {@code desired} at which one font pixel covers a whole number of
     * screen pixels (never less than one).
     *
     * @param pixelsPerUnit screen pixels per unit before this scale is applied
     */
    static float crispScale(float desired, float pixelsPerUnit) {
        return Math.max(1, Math.round(desired * pixelsPerUnit)) / pixelsPerUnit;
    }

    /**
     * {@link #crispScale} of {@code desired}, stepped down towards that of {@code min} until a
     * line {@code width} font pixels wide fits in {@code maxWidth} units.
     */
    static float fitScale(float desired, float min, float width, float maxWidth, float pixelsPerUnit) {
        int steps = Math.max(1, Math.round(desired * pixelsPerUnit));
        int floor = Math.min(steps, Math.max(1, Math.round(min * pixelsPerUnit)));
        while (steps > floor && width * steps / pixelsPerUnit > maxWidth) {
            steps--;
        }
        return steps / pixelsPerUnit;
    }

    /**
     * Moves a local coordinate so it lands on a whole screen pixel.
     *
     * @param origin   screen position (GUI units) of local zero
     * @param scale    GUI units per local unit
     * @param guiScale screen pixels per GUI unit
     */
    static float snap(float local, float origin, float scale, float guiScale) {
        float pixel = Math.round((origin + local * scale) * guiScale);
        return (pixel / guiScale - origin) / scale;
    }
}
