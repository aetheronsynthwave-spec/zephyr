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
import dev.zephyr.modules.render.RenderUtil;
import dev.zephyr.modules.render.WorldRenderParticipant;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    /** HUD = the on-screen panel; the rest draw the lyrics inside the world. */
    private final ModeSetting display = s(new ModeSetting("display", "Display",
            List.of("HUD", "GROUND", "SKY", "3D"), "HUD"));
    private final NumberSetting worldScale = num("worldScale", "World text scale", 0.25, 4.0, 0.05, 1.0);
    private final NumberSetting worldDist = num("worldDist", "Distance ahead (ground / 3D)", 2, 24, 0.5, 7);
    private final NumberSetting depth = num("depth", "3D depth layers", 1, 14, 1, 7);
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

    /* Per-frame layout, computed in measure() and drawn in renderContent(). */
    private final float[] size = new float[2];
    private String shownCurrent = "";
    private String shownNext;
    private String shownTrack;
    private String trackSource;
    private String trackLine;

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

    /** Per-frame content, filled by {@link #prepare()} for both the HUD and the world modes. */
    private long posMs;
    private float progress = -1f;
    private int lineIdx = -2;
    private long lineChangedAt;
    private Text curText = Text.empty();
    private Text nextText;
    private Text trackText;
    private float curW;
    private float nextW;
    private float trackW;
    private String curSrc = "";
    private String nextSrc;
    private String trackSrc;

    private String[] unsyncedFor;
    private String unsyncedLine = "";
    /** GROUND mode: surface height is sampled once per client tick, not every frame. */
    private int groundSampleTick = Integer.MIN_VALUE;
    private double groundTarget = Double.NaN;

    private boolean anchorSet;
    private float anchorYaw;
    private String lastDisplay = "HUD";
    private double groundY = Double.NaN;

    /**
     * Computes what to show this frame. @return false when there is nothing to draw (Spotify
     * not running / still waiting), in which case nothing is rendered anywhere.
     */
    private boolean prepare() {
        State s = state;
        String current;
        String next = null;
        progress = -1f;
        int idx = -1;
        if (s.track == null || !s.status.isEmpty() || s.lines.length == 0) {
            if (s.track == null && (s.status.startsWith("Waiting") || s.status.startsWith("Spotify is not"))) {
                return false;
            }
            current = s.status;
        } else if (s.synced) {
            long now = s.pausedSinceMs != 0 ? s.pausedSinceMs : System.currentTimeMillis();
            posMs = now - s.startedAtMs - s.pausedTotalMs + offsetMs.get().longValue();
            idx = lineAt(s.times, posMs);
            current = idx < 0 ? "♪" : s.lines[idx];
            if (idx + 1 < s.lines.length) {
                next = s.lines[idx + 1];
            }
        } else {
            if (s.lines != unsyncedFor) {
                unsyncedFor = s.lines;
                unsyncedLine = "(unsynced) " + s.lines[0];
            }
            current = unsyncedLine;
        }
        if (s.durationMs > 0 && s.track != null) {
            long now = s.pausedSinceMs != 0 ? s.pausedSinceMs : System.currentTimeMillis();
            long pos = now - s.startedAtMs - s.pausedTotalMs + offsetMs.get().longValue();
            progress = Math.max(0f, Math.min(1f, pos / (float) s.durationMs));
        }
        if (idx != lineIdx) {
            lineIdx = idx;
            lineChangedAt = System.currentTimeMillis();
        }
        if (s.track != trackSource) {
            trackSource = s.track;
            trackLine = s.track == null ? null : "♫ " + s.track;
        }
        shownCurrent = current;
        shownNext = next != null && showNext.get() ? next : null;
        shownTrack = showTrack.get() ? trackLine : null;

        var tr = MinecraftClient.getInstance().textRenderer;
        int limit = maxWidth.get().intValue();
        if (!current.equals(curSrc)) {
            curSrc = current;
            String c = clip(tr, current, limit);
            curText = Text.literal(c);
            curW = tr.getWidth(c);
        }
        if (shownNext == null) {
            nextSrc = null;
            nextText = null;
            nextW = 0f;
        } else if (!shownNext.equals(nextSrc)) {
            nextSrc = shownNext;
            String c = clip(tr, shownNext, limit);
            nextText = Text.literal(c);
            nextW = tr.getWidth(c);
        }
        if (shownTrack == null) {
            trackSrc = null;
            trackText = null;
            trackW = 0f;
        } else if (!shownTrack.equals(trackSrc)) {
            trackSrc = shownTrack;
            String c = clip(tr, shownTrack, limit);
            trackText = Text.literal(c);
            trackW = tr.getWidth(c);
        }
        return true;
    }

    private static String clip(TextRenderer tr, String s, int limit) {
        if (tr.getWidth(s) <= limit) {
            return s;
        }
        return tr.trimToWidth(s, limit - tr.getWidth("…")) + "…";
    }

    /** 0..1 progress of the line-change animation (ease-out cubic); 1 when animation is off. */
    private float lineEase() {
        if (!smoothLines.get()) {
            return 1f;
        }
        float p = Math.min(1f, (System.currentTimeMillis() - lineChangedAt) / 320f);
        float q = 1f - p;
        return 1f - q * q * q;
    }

    // ------------------------------------------------------------------
    // HUD rendering
    // ------------------------------------------------------------------

    private static final float CUR_SCALE = 1.12f;
    private static final float ROW = 11f;

    @Override
    protected float[] measure() {
        // World modes render (and prepare) in onWorldRender; doing it here too doubled the work.
        if (!display.is("HUD") || !prepare()) {
            size[0] = 0f;
            size[1] = 0f;
            return size;
        }
        float w = Math.max(curW * CUR_SCALE, Math.max(nextW, trackW));
        w = Math.max(w, 90f);
        float h = ROW * CUR_SCALE;
        if (trackText != null) {
            h += ROW + 1f;
        }
        if (nextText != null) {
            h += ROW + 1f;
        }
        if (showProgress.get() && progress >= 0f) {
            h += 6f;
        }
        size[0] = w + 8f;
        size[1] = h;
        return size;
    }

    @Override
    protected void renderContent(DrawContext ctx, float x, float y, float alpha) {
        var tr = MinecraftClient.getInstance().textRenderer;
        float cx = x + size[0] / 2f;
        float ly = y;
        float ease = lineEase();
        if (trackText != null) {
            drawVanilla(ctx, tr, trackText, trackW, cx, ly, accent(), alpha);
            ly += ROW + 1f;
        }
        if (showProgress.get() && progress >= 0f) {
            float bw = size[0] - 8f;
            float bx = x + 4f;
            Ui.roundRect(ctx, bx, ly + 1f, bw, 2f, 1f, Ui.alpha(textFaint(), 0.45f * alpha));
            if (progress > 0.002f) {
                Ui.roundRect(ctx, bx, ly + 1f, Math.max(2f, bw * progress), 2f, 1f, Ui.alpha(accent(), alpha));
            }
            ly += 6f;
        }
        // Current line: slightly larger, slides up and fades in when the line changes.
        float slide = (1f - ease) * 5f;
        float curAlpha = alpha * (0.3f + 0.7f * ease);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(cx, ly + slide);
        ctx.getMatrices().scale(CUR_SCALE, CUR_SCALE);
        drawVanilla(ctx, tr, curText, curW, 0f, 0f, text(), curAlpha);
        ctx.getMatrices().popMatrix();
        ly += ROW * CUR_SCALE + 1f;
        if (nextText != null) {
            drawVanilla(ctx, tr, nextText, nextW, cx, ly, textDim(), alpha * (0.55f + 0.45f * ease));
        }
    }

    private void drawVanilla(DrawContext ctx, TextRenderer tr, Text line, float width, float cx, float y,
                             int color, float alpha) {
        ctx.drawText(tr, line, Math.round(cx - width / 2f), Math.round(y), Ui.alpha(color, alpha), textShadow());
    }

    // ------------------------------------------------------------------
    // World rendering (GROUND / SKY / 3D)
    // ------------------------------------------------------------------

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
            groundY = Double.NaN;
        }
        float yaw = lockDir.get() ? anchorYaw : client.player.getYaw();
        double yr = Math.toRadians(yaw);
        double fx = -Math.sin(yr);
        double fz = Math.cos(yr);
        Vec3d cam = RenderUtil.cameraPos();
        float t = (System.currentTimeMillis() % 3_600_000L) / 1000f;
        float ease = lineEase();
        float scale = (float) worldScale.get().doubleValue();
        MatrixStack m = wctx.matrices();
        var consumers = wctx.consumers();
        boolean through = seeThrough.get();
        int accent = accent();
        int layers = 1;
        double px;
        double py;
        double pz;
        float rowSlide = (1f - ease) * 5f;

        m.push();
        switch (mode) {
            case "GROUND" -> {
                double d = worldDist.get();
                px = cam.x + fx * d;
                pz = cam.z + fz * d;
                int tick = RenderUtil.currentTick();
                if (tick != groundSampleTick || Double.isNaN(groundTarget)) {
                    groundSampleTick = tick; // one raycast per tick instead of per frame
                    groundTarget = groundHeight(client, px, pz);
                }
                double target = groundTarget;
                groundY = Double.isNaN(groundY) ? target : groundY + (target - groundY) * 0.2;
                py = groundY + 0.03;
                m.translate(px - cam.x, py - cam.y, pz - cam.z);
                m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw));
                m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f));
                float sc = 0.08f * scale;
                m.scale(sc, -sc, sc);
            }
            case "SKY" -> {
                double el = Math.toRadians(32.0);
                double dist = 80.0;
                px = cam.x + fx * Math.cos(el) * dist;
                pz = cam.z + fz * Math.cos(el) * dist;
                py = cam.y + Math.sin(el) * dist + Math.sin(t * 0.7f) * 1.5;
                m.translate(px - cam.x, py - cam.y, pz - cam.z);
                var camera = client.gameRenderer.getCamera();
                m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - camera.getYaw()));
                m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-camera.getPitch()));
                float sc = 0.01f * (float) dist * scale;
                m.scale(sc, -sc, sc);
                through = true; // terrain must never hide the sky text
            }
            default -> { // 3D
                double d = worldDist.get();
                px = cam.x + fx * d;
                pz = cam.z + fz * d;
                py = cam.y + 0.35 + Math.sin(t * 1.4f) * 0.08;
                m.translate(px - cam.x, py - cam.y, pz - cam.z);
                // Slow sway so the thickness catches the eye; layers below give the extrusion.
                m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw + (float) Math.sin(t * 0.9f) * 16f));
                float sc = 0.03f * scale;
                m.scale(sc, -sc, sc);
                layers = depth.get().intValue() + 1;
            }
        }

        int bg = mode.equals("GROUND") ? 0x55000000 : 0;
        float step = 1.3f;
        for (int layer = layers - 1; layer >= 0; layer--) {
            boolean front = layer == 0;
            m.push();
            m.translate(0f, 0f, -layer * step);
            float fade = 0.3f + 0.7f * ease;
            float shade = front ? 1f : Math.max(0.12f, 0.55f - layer * 0.04f);
            int curColor = front ? Ui.alpha(text(), fade) : Ui.alpha(shadeOf(accent, shade), 1f);
            int trackColor = front ? Ui.alpha(accent, 1f) : Ui.alpha(shadeOf(accent, shade * 0.8f), 1f);
            int nextColor = front ? Ui.alpha(textDim(), 0.7f + 0.3f * ease) : Ui.alpha(shadeOf(accent, shade * 0.6f), 1f);
            int rowBg = front ? bg : 0;
            float y = -24f;
            if (trackText != null) {
                drawWorldRow(m, consumers, trackText, trackW, y, 0.85f, trackColor, rowBg, through);
            }
            drawWorldRow(m, consumers, curText, curW, -6f + rowSlide, 1.6f, curColor, rowBg, through);
            if (nextText != null) {
                drawWorldRow(m, consumers, nextText, nextW, 17f, 1.0f, nextColor, rowBg, through);
            }
            m.pop();
        }
        m.pop();
    }

    private void drawWorldRow(MatrixStack m, net.minecraft.client.render.VertexConsumerProvider consumers, Text text,
                              float width, float y, float mul, int color, int bg, boolean through) {
        var tr = MinecraftClient.getInstance().textRenderer;
        m.push();
        m.translate(0f, y, 0f);
        m.scale(mul, mul, 1f);
        tr.draw(text, -width / 2f, 0f, color, false, m.peek().getPositionMatrix(), consumers,
                through ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL,
                bg, 0xF000F0);
        m.pop();
    }

    /** Darkened copy of the accent for the back layers of the 3D extrusion. */
    private static int shadeOf(int argb, float k) {
        int r = (int) (((argb >> 16) & 0xFF) * k);
        int g = (int) (((argb >> 8) & 0xFF) * k);
        int b = (int) ((argb & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Y of the first solid surface below the point (searching 8 blocks down), else the player's feet. */
    private double groundHeight(MinecraftClient client, double x, double z) {
        var p = client.player;
        Vec3d from = new Vec3d(x, p.getY() + 2.0, z);
        Vec3d to = new Vec3d(x, p.getY() - 6.0, z);
        var hit = client.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getPos().y : p.getY();
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
