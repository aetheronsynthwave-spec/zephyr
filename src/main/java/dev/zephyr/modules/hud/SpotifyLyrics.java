package dev.zephyr.modules.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.hud.HudModule;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.ModeSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import dev.zephyr.modules.render.WorldRenderParticipant;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SpotifyLyrics - synced lyrics for whatever the Spotify desktop app is playing, shown on
 * the HUD. No login or API key:
 * <ul>
 *   <li><b>Now playing:</b> the Spotify window title is "Artist - Song" while playing and just
 *       "Spotify ..." while paused. It is read once a second on a background thread via JNA
 *       (shipped with Minecraft): EnumWindows, filtered to the Spotify.exe process.</li>
 *   <li><b>Lyrics:</b> fetched once per track from LRCLIB (lrclib.net, free and keyless), using
 *       its time-stamped "syncedLyrics" (LRC format), falling back to plain lyrics.</li>
 *   <li><b>Position:</b> the title does not expose the playback position, so the clock starts
 *       when a new track appears and pauses while Spotify is paused. If you start the game
 *       mid-song, use "Sync offset" (or press play on a fresh track) to line it up.</li>
 * </ul>
 * Windows only (the window-title probe). Nothing is drawn while nothing is playing.
 */
public final class SpotifyLyrics extends HudModule implements WorldRenderParticipant {

    private final NumberSetting offsetMs = num("offset", "Sync offset ms", -10000, 10000, 100, 0);
    private final BooleanSetting showTrack = bool("track", "Show song name", true);
    private final BooleanSetting showNext = bool("next", "Show next line", true);
    private final BooleanSetting showProgress = bool("progress", "Progress bar", true);
    private final BooleanSetting smoothLines = bool("smooth", "Line transition animation", true);
    private final NumberSetting maxWidth = num("maxWidth", "Max line width", 120, 480, 10, 260);

    /** HUD = the on-screen panel; SKY and 3D draw the lyrics inside the world. */
    private final ModeSetting display = s(new ModeSetting("display", "Display",
            List.of("HUD", "SKY", "3D"), "HUD"));
    private final NumberSetting worldScale = num("worldScale", "World text scale", 0.25, 4.0, 0.05, 1.0);
    private final NumberSetting worldDist = num("worldDist", "Distance ahead (3D)", 2, 24, 0.5, 7);
    private final NumberSetting depth = num("depth", "3D depth layers", 1, 14, 1, 6);
    private final BooleanSetting lockDir = bool("lockDir", "Lock direction (set when enabled)", true);
    private final BooleanSetting seeThrough = bool("throughWalls", "World text through walls", false);

    private static final Pattern LRC = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** Immutable snapshot published by the background thread, read by the render thread. */
    private record State(String track, String status, long[] times, String[] lines, boolean synced,
                         long startedAtMs, long pausedTotalMs, long pausedSinceMs, long durationMs) {
        State(String track, String status, long[] times, String[] lines, boolean synced,
              long startedAtMs, long pausedTotalMs, long pausedSinceMs) {
            this(track, status, times, lines, synced, startedAtMs, pausedTotalMs, pausedSinceMs, 0L);
        }
    }

    private volatile State state = new State(null, "Waiting for Spotify...", new long[0], new String[0],
            false, 0, 0, 0);
    private ScheduledExecutorService worker;
    /** Lyrics downloads run here, so a slow LRCLIB request never stalls the 1 s window poll. */
    private java.util.concurrent.ExecutorService fetcher;
    private ScheduledFuture<?> task;
    private String lastTitleTrack;

    public SpotifyLyrics() {
        super("SpotifyLyrics", ModuleCategory.RENDER, 50, 5, "CENTER");
    }

    @Override
    protected void onEnable() {
        anchorSet = false;
        if (!Platform.isWindows()) {
            state = new State(null, "SpotifyLyrics needs Windows", new long[0], new String[0], false, 0, 0, 0);
            return;
        }
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Zephyr-SpotifyLyrics");
            t.setDaemon(true);
            return t;
        });
        fetcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Zephyr-SpotifyLyrics-Fetch");
            t.setDaemon(true);
            return t;
        });
        task = worker.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel(true);
        }
        if (worker != null) {
            worker.shutdownNow();
        }
        if (fetcher != null) {
            fetcher.shutdownNow();
        }
        fetcher = null;
        worker = null;
        task = null;
        lastTitleTrack = null;
    }

    // ------------------------------------------------------------------
    // Background thread
    // ------------------------------------------------------------------

    private void poll() {
        try {
            String title = spotifyTitle();
            long now = System.currentTimeMillis();
            State s = state;
            if (title == null) {
                state = new State(null, "Spotify is not running", new long[0], new String[0], false, 0, 0, 0);
                lastTitleTrack = null;
                return;
            }
            boolean playing = title.contains(" - ");
            if (!playing) {
                // Paused: freeze the clock.
                if (s.track != null && s.pausedSinceMs == 0) {
                    state = new State(s.track, s.status, s.times, s.lines, s.synced, s.startedAtMs,
                            s.pausedTotalMs, now, s.durationMs);
                }
                return;
            }
            if (title.equals(lastTitleTrack)) {
                if (s.pausedSinceMs != 0) { // resumed
                    state = new State(s.track, s.status, s.times, s.lines, s.synced, s.startedAtMs,
                            s.pausedTotalMs + (now - s.pausedSinceMs), 0, s.durationMs);
                }
                return;
            }
            lastTitleTrack = title;
            int cut = title.indexOf(" - ");
            String artist = title.substring(0, cut).trim();
            String song = title.substring(cut + 3).trim();
            state = new State(title, "Loading lyrics...", new long[0], new String[0], false, now, 0, 0);
            java.util.concurrent.ExecutorService f = fetcher;
            if (f != null && !f.isShutdown()) {
                f.execute(() -> fetch(title, artist, song, now)); // publish() drops it if the track changed
            }
        } catch (Throwable t) {
            // Never kill the scheduler: a failed poll just tries again next second.
        }
    }

    private void fetch(String track, String artist, String song, long startedAt) {
        try {
            String url = "https://lrclib.net/api/get?artist_name=" + enc(artist) + "&track_name=" + enc(song);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "ZephyrClient-Lyrics/1.0")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                publish(track, "No lyrics found", new long[0], new String[0], false, startedAt);
                return;
            }
            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonElement dur = json.get("duration");
            long durationMs = dur == null || dur.isJsonNull() ? 0L : Math.round(dur.getAsDouble() * 1000.0);
            String synced = str(json.get("syncedLyrics"));
            if (synced != null && !synced.isBlank()) {
                List<Long> times = new ArrayList<>();
                List<String> lines = new ArrayList<>();
                for (String raw : synced.split("\n")) {
                    Matcher m = LRC.matcher(raw.trim());
                    if (m.matches()) {
                        long ms = Long.parseLong(m.group(1)) * 60_000L + Long.parseLong(m.group(2)) * 1000L;
                        if (m.group(3) != null) {
                            String frac = (m.group(3) + "00").substring(0, 3);
                            ms += Long.parseLong(frac);
                        }
                        times.add(ms);
                        lines.add(m.group(4).isBlank() ? "♪" : m.group(4));
                    }
                }
                long[] t = new long[times.size()];
                for (int i = 0; i < t.length; i++) {
                    t[i] = times.get(i);
                }
                publish(track, "", t, lines.toArray(new String[0]), true, startedAt, durationMs);
                return;
            }
            String plain = str(json.get("plainLyrics"));
            if (plain != null && !plain.isBlank()) {
                publish(track, "", new long[0], plain.split("\n"), false, startedAt, durationMs);
            } else {
                publish(track, "Instrumental / no lyrics", new long[0], new String[0], false, startedAt);
            }
        } catch (Exception e) {
            publish(track, "Lyrics unavailable (offline?)", new long[0], new String[0], false, startedAt);
        }
    }

    /** Publishes lyrics only if the same track is still current, keeping its clock. */
    private void publish(String track, String status, long[] times, String[] lines, boolean synced, long startedAt) {
        publish(track, status, times, lines, synced, startedAt, 0L);
    }

    private void publish(String track, String status, long[] times, String[] lines, boolean synced, long startedAt,
                         long durationMs) {
        State s = state;
        if (track.equals(s.track)) {
            state = new State(track, status, times, lines, synced, s.startedAtMs, s.pausedTotalMs, s.pausedSinceMs,
                    durationMs);
        }
    }

    private static String str(JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Title of the visible window owned by Spotify.exe, or null if Spotify is not running. */
    private static String spotifyTitle() {
        final String[] found = {null};
        final char[] buf = new char[512];
        final char[] path = new char[1024];
        User32.INSTANCE.EnumWindows((WinDef.HWND hwnd, com.sun.jna.Pointer data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }
            int len = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            if (len <= 0) {
                return true;
            }
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            WinNT.HANDLE h = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.getValue());
            if (h == null) {
                return true;
            }
            try {
                IntByReference size = new IntByReference(path.length);
                if (Kernel32.INSTANCE.QueryFullProcessImageName(h, 0, path, size)) {
                    String exe = new String(path, 0, size.getValue());
                    if (exe.toLowerCase().endsWith("\\spotify.exe")) {
                        found[0] = new String(buf, 0, len);
                        return false; // stop enumerating
                    }
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(h);
            }
            return true;
        }, null);
        return found[0];
    }

    // ------------------------------------------------------------------
    // Shared per-frame preparation
    // ------------------------------------------------------------------

    /** A clipped line with its width cached, so nothing is measured or built per frame. */
    private record Line(Text text, float width) {
    }

    private static final long TRANSITION_MS = 320L;

    /* Filled by prepare() once per frame for both the HUD and the world modes. */
    private float progress = -1f;
    private int lineIdx = -2;
    private long lineChangedAt;
    private int laidOutLimit = -1;
    private String curSrc;
    private String nextSrc;
    private String trackSrc;
    private Line cur;
    /** The line that is animating out. */
    private Line prev;
    private Line next;
    private Line track;

    private String[] unsyncedFor;
    private String unsyncedLine = "";

    private boolean anchorSet;
    private float anchorYaw;
    private String lastDisplay = "HUD";

    /**
     * Computes what to show this frame. @return false when there is nothing to draw (Spotify
     * not running / still waiting), in which case nothing is rendered anywhere.
     */
    private boolean prepare() {
        State s = state;
        long wall = System.currentTimeMillis();
        long pos = (s.pausedSinceMs != 0 ? s.pausedSinceMs : wall) - s.startedAtMs - s.pausedTotalMs
                + offsetMs.get().longValue();
        String current;
        String upcoming = null;
        int idx = -1;
        if (s.track == null || !s.status.isEmpty() || s.lines.length == 0) {
            if (s.track == null && (s.status.startsWith("Waiting") || s.status.startsWith("Spotify is not"))) {
                curSrc = null;
                cur = null;
                prev = null;
                return false;
            }
            current = s.status;
        } else if (s.synced) {
            idx = lineAt(s.times, pos);
            current = idx < 0 ? "♪" : s.lines[idx];
            if (idx + 1 < s.lines.length) {
                upcoming = s.lines[idx + 1];
            }
        } else {
            if (s.lines != unsyncedFor) {
                unsyncedFor = s.lines;
                unsyncedLine = "(unsynced) " + s.lines[0];
            }
            current = unsyncedLine;
        }
        progress = s.durationMs > 0 && s.track != null
                ? Math.max(0f, Math.min(1f, pos / (float) s.durationMs))
                : -1f;
        if (!showNext.get()) {
            upcoming = null;
        }
        String title = showTrack.get() ? s.track : null;

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int limit = maxWidth.get().intValue();
        boolean relayout = limit != laidOutLimit;
        laidOutLimit = limit;
        // The index matters too: a repeated lyric is still a new line.
        if (idx != lineIdx || !current.equals(curSrc)) {
            prev = cur;
            lineIdx = idx;
            curSrc = current;
            cur = line(tr, current, limit);
            lineChangedAt = wall;
        } else if (relayout) {
            cur = line(tr, current, limit);
        }
        if (relayout || !Objects.equals(upcoming, nextSrc)) {
            nextSrc = upcoming;
            next = upcoming == null ? null : line(tr, upcoming, limit);
        }
        if (relayout || !Objects.equals(title, trackSrc)) {
            trackSrc = title;
            track = title == null ? null : line(tr, "♫ " + title, limit);
        }
        return true;
    }

    private static Line line(TextRenderer tr, String s, int limit) {
        String clipped = tr.getWidth(s) <= limit ? s : tr.trimToWidth(s, limit - tr.getWidth("…")) + "…";
        return new Line(Text.literal(clipped), tr.getWidth(clipped));
    }

    /** 0..1 progress of the line-change animation; 1 when animation is off. */
    private float lineEase() {
        return LyricsLayout.ease(System.currentTimeMillis() - lineChangedAt,
                smoothLines.get() ? TRANSITION_MS : 0L);
    }

    // ------------------------------------------------------------------
    // HUD rendering
    // ------------------------------------------------------------------

    private static final float CUR_SCALE = 1.5f;
    private static final float TEXT_H = 9f;
    private static final float GAP = 4f;
    private static final float BAR_H = 2f;
    private static final float MIN_WIDTH = 120f;
    private static final float SIDE = 4f;
    /** How far a finished line travels while fading out. */
    private static final float EXIT_RISE = 8f;

    /* HUD scales for this frame, snapped to whole screen pixels. */
    private float smallScale = 1f;
    private float bigScale = CUR_SCALE;
    private final float[] size = new float[2];
    /** This frame's on-screen box, used to snap text to whole pixels. */
    private float[] box;

    private float pixelsPerUnit() {
        return (float) MinecraftClient.getInstance().getWindow().getScaleFactor() * hudScale();
    }

    private float currentScale(Line l, float ppu) {
        return LyricsLayout.fitScale(CUR_SCALE, smallScale, l.width, laidOutLimit, ppu);
    }

    @Override
    protected float[] measure() {
        // World modes render (and prepare) in onWorldRender; doing it here too doubled the work.
        if (!display.is("HUD") || !prepare()) {
            size[0] = 0f;
            size[1] = 0f;
            return size;
        }
        float ppu = pixelsPerUnit();
        smallScale = LyricsLayout.crispScale(1f, ppu);
        bigScale = Math.max(LyricsLayout.crispScale(CUR_SCALE, ppu), smallScale + 1f / ppu);

        float w = Math.max(MIN_WIDTH, cur.width * currentScale(cur, ppu));
        if (next != null) {
            w = Math.max(w, next.width * smallScale);
        }
        if (track != null) {
            w = Math.max(w, track.width * smallScale);
        }
        // Ease the panel from the old width instead of jumping with every line.
        float ease = lineEase();
        if (ease < 1f && size[0] > 0f) {
            w = LyricsLayout.lerp(size[0] - SIDE * 2f, w, ease);
        }

        // Rows are reserved by setting, so the height does not change from line to line.
        float h = TEXT_H * bigScale;
        if (showTrack.get()) {
            h += TEXT_H * smallScale + GAP;
        }
        if (showNext.get()) {
            h += GAP + TEXT_H * smallScale;
        }
        if (showProgress.get()) {
            h += GAP + BAR_H;
        }
        size[0] = w + SIDE * 2f;
        size[1] = h;
        return size;
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        box = screenBounds();
        float ppu = pixelsPerUnit();
        float cx = x + size[0] / 2f;
        float top = y;
        if (showTrack.get()) {
            if (track != null) {
                drawHudLine(ctx, track, cx, top, smallScale, accent(), alpha, textShadow());
            }
            top += TEXT_H * smallScale + GAP;
        }
        float curY = top;
        float nextY = curY + TEXT_H * bigScale + GAP;
        float ease = lineEase();
        float out = 1f - ease;
        if (prev != null && ease < 1f) {
            drawHudLine(ctx, prev, cx, curY - EXIT_RISE * ease, currentScale(prev, ppu), text(),
                    alpha * out * out, true);
        }
        // The new line rises out of the "next" row, growing and brightening as it arrives.
        float target = currentScale(cur, ppu);
        float scale = LyricsLayout.lerp(smallScale, target, ease);
        float rest = curY + TEXT_H * (bigScale - target) / 2f;
        float from = showNext.get() ? nextY : curY + GAP;
        drawHudLine(ctx, cur, cx, LyricsLayout.lerp(from, rest, ease), scale,
                Ui.mix(textDim(), text(), ease), alpha * (0.6f + 0.4f * ease), true);
        top = curY + TEXT_H * bigScale;
        if (showNext.get()) {
            if (next != null) {
                drawHudLine(ctx, next, cx, nextY + GAP * out, smallScale, textDim(), alpha * ease, textShadow());
            }
            top = nextY + TEXT_H * smallScale;
        }
        if (showProgress.get() && progress >= 0f) {
            float bx = x + SIDE;
            float bw = size[0] - SIDE * 2f;
            float by = top + GAP;
            Ui.roundRect(ctx, bx, by, bw, BAR_H, BAR_H / 2f, Ui.alpha(textFaint(), 0.55f * alpha));
            float filled = bw * progress;
            if (filled > 0.5f) {
                Ui.roundRect(ctx, bx, by, filled, BAR_H, BAR_H / 2f, Ui.alpha(accent(), alpha));
            }
            Ui.circle(ctx, bx + filled, by + BAR_H / 2f, BAR_H, Ui.alpha(text(), alpha));
        }
    }

    private void drawHudLine(DrawContext ctx, Line line, float cx, float y, float scale, int color,
                             float alpha, boolean shadow) {
        int argb = Ui.alpha(color, alpha);
        if (!visible(argb)) {
            return;
        }
        float px = cx - line.width * scale / 2f;
        float py = y;
        if (box != null) {
            float gui = (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
            px = LyricsLayout.snap(px, box[0], hudScale(), gui);
            py = LyricsLayout.snap(py, box[1], hudScale(), gui);
        }
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(px, py);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, line.text, 0, 0, argb, shadow);
        ctx.getMatrices().popMatrix();
    }

    /** Nearly transparent text is skipped: the font renderer may treat very low alpha as opaque. */
    private static boolean visible(int argb) {
        return (argb >>> 24) >= 8;
    }

    // ------------------------------------------------------------------
    // World rendering (SKY / 3D)
    // ------------------------------------------------------------------

    private static final float TRACK_Y = -22f;
    private static final float TRACK_MUL = 0.8f;
    private static final float CUR_Y = -10f;
    private static final float CUR_MUL = 1.6f;
    private static final float NEXT_Y = 10f;
    private static final float LAYER_STEP = 0.45f;
    private static final double SKY_DISTANCE = 80.0;

    @Override
    public void onWorldRender(WorldRenderContext wctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || wctx.consumers() == null || wctx.matrices() == null
                || client.options.hudHidden) {
            return;
        }
        String mode = display.get();
        if (!mode.equals(lastDisplay)) {
            lastDisplay = mode;
            anchorSet = false;
        }
        if (mode.equals("HUD") || !prepare()) {
            return;
        }
        if (!anchorSet) {
            anchorYaw = client.player.getYaw();
            anchorSet = true;
        }
        float yaw = lockDir.get() ? anchorYaw : client.player.getYaw();
        double yr = Math.toRadians(yaw);
        double fx = -Math.sin(yr);
        double fz = Math.cos(yr);
        float t = (System.currentTimeMillis() % 3_600_000L) / 1000f;
        float scale = (float) worldScale.get().doubleValue();
        MatrixStack m = wctx.matrices();
        VertexConsumerProvider consumers = wctx.consumers();
        boolean sky = mode.equals("SKY");
        boolean through = sky || seeThrough.get(); // terrain must never hide the sky text

        m.push();
        if (sky) {
            double el = Math.toRadians(32.0);
            double px = fx * Math.cos(el) * SKY_DISTANCE;
            double pz = fz * Math.cos(el) * SKY_DISTANCE;
            double py = Math.sin(el) * SKY_DISTANCE + Math.sin(t * 0.7f) * 0.6;
            m.translate(px, py, pz);
            var camera = client.gameRenderer.getCamera();
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - camera.getYaw()));
            m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-camera.getPitch()));
            float sc = 0.003f * (float) SKY_DISTANCE * scale;
            m.scale(sc, -sc, sc);
        } else {
            double d = worldDist.get();
            double px = fx * d;
            double pz = fz * d;
            double py = 0.35 + Math.sin(t * 1.4f) * 0.05; // relative to the camera
            m.translate(px, py, pz);
            // A slow sway shows off the extrusion without ever turning the text hard to read.
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw + (float) Math.sin(t * 0.9f) * 10f));
            float sc = 0.022f * scale;
            m.scale(sc, -sc, sc);
        }
        drawWorldLyrics(m, consumers, sky ? 0 : depth.get().intValue(), through);
        m.pop();
    }

    private void drawWorldLyrics(MatrixStack m, VertexConsumerProvider consumers, int layers, boolean through) {
        float ease = lineEase();
        float out = 1f - ease;
        int accent = accent();
        float plate = 0.65f * bgAlpha();

        if (track != null) {
            drawWorldRow(m, consumers, track, TRACK_Y, TRACK_MUL, 0f, accent, true,
                    Ui.alpha(0xFF000000, plate), through);
        }
        if (prev != null && ease < 1f) {
            drawWorldRow(m, consumers, prev, CUR_Y - EXIT_RISE * ease, worldMul(prev), 0f,
                    Ui.alpha(text(), out * out), false, 0, through);
        }
        float y = LyricsLayout.lerp(NEXT_Y, CUR_Y, ease);
        float mul = LyricsLayout.lerp(1f, worldMul(cur), ease);
        int plateColor = Ui.alpha(0xFF000000, plate * ease);
        // Back to front. The backdrop belongs to the deepest layer so it never covers the extrusion.
        for (int layer = layers; layer >= 1; layer--) {
            float k = LyricsLayout.lerp(0.6f, 0.2f, (layer - 1) / (float) Math.max(1, layers - 1));
            drawWorldRow(m, consumers, cur, y, mul, -layer * LAYER_STEP, Ui.alpha(shadeOf(accent, k), ease), false,
                    layer == layers ? plateColor : 0, through);
        }
        drawWorldRow(m, consumers, cur, y, mul, 0f, Ui.alpha(Ui.mix(textDim(), text(), ease), 0.6f + 0.4f * ease),
                layers == 0, layers == 0 ? plateColor : 0, through);
        if (next != null) {
            drawWorldRow(m, consumers, next, NEXT_Y + GAP * out, 1f, 0f, Ui.alpha(textDim(), ease), true,
                    Ui.alpha(0xFF000000, plate * ease), through);
        }
    }

    private float worldMul(Line l) {
        return Math.max(1f, Math.min(CUR_MUL, laidOutLimit / Math.max(1f, l.width)));
    }

    private void drawWorldRow(MatrixStack m, VertexConsumerProvider consumers, Line line, float y, float mul, float z,
                              int color, boolean shadow, int backdrop, boolean through) {
        if (!visible(color)) {
            return;
        }
        m.push();
        m.translate(0f, y, z);
        m.scale(mul, mul, 1f);
        MinecraftClient.getInstance().textRenderer.draw(line.text, -line.width / 2f, 0f, color, shadow,
                m.peek().getPositionMatrix(), consumers,
                through ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL,
                backdrop, 0xF000F0);
        m.pop();
    }

    /** Darkened copy of the accent for the back layers of the 3D extrusion. */
    private static int shadeOf(int argb, float k) {
        int r = (int) (((argb >> 16) & 0xFF) * k);
        int g = (int) (((argb >> 8) & 0xFF) * k);
        int b = (int) ((argb & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Index of the last line whose timestamp is <= pos (binary search), or -1. */
    private static int lineAt(long[] times, long pos) {
        int lo = 0, hi = times.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    @Override
    public String hudLabel() {
        State s = state;
        return s.track == null ? name : name + " · ♪";
    }
}
