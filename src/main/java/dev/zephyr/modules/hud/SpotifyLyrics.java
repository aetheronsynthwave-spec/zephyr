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
import dev.zephyr.core.Module;
import dev.zephyr.core.ModuleCategory;
import dev.zephyr.core.settings.BooleanSetting;
import dev.zephyr.core.settings.NumberSetting;
import dev.zephyr.gui.render.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

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
public final class SpotifyLyrics extends Module {

    private final NumberSetting offsetMs = num("offset", "Sync offset ms", -10000, 10000, 100, 0);
    private final NumberSetting scale = num("scale", "Scale", 0.6, 2.0, 0.05, 1.0);
    private final NumberSetting yPct = num("yPct", "Y position %", 0, 100, 0.5, 8);
    private final BooleanSetting showTrack = bool("track", "Show song name", true);
    private final BooleanSetting showNext = bool("next", "Show next line", true);

    private static final Pattern LRC = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** Immutable snapshot published by the background thread, read by the render thread. */
    private record State(String track, String status, long[] times, String[] lines, boolean synced,
                         long startedAtMs, long pausedTotalMs, long pausedSinceMs) {
    }

    private volatile State state = new State(null, "Waiting for Spotify...", new long[0], new String[0],
            false, 0, 0, 0);
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> task;
    private String lastTitleTrack;

    public SpotifyLyrics() {
        super("SpotifyLyrics", ModuleCategory.RENDER, 0);
    }

    @Override
    protected void onEnable() {
        if (!Platform.isWindows()) {
            state = new State(null, "SpotifyLyrics needs Windows", new long[0], new String[0], false, 0, 0, 0);
            return;
        }
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Zephyr-SpotifyLyrics");
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
                            s.pausedTotalMs, now);
                }
                return;
            }
            if (title.equals(lastTitleTrack)) {
                if (s.pausedSinceMs != 0) { // resumed
                    state = new State(s.track, s.status, s.times, s.lines, s.synced, s.startedAtMs,
                            s.pausedTotalMs + (now - s.pausedSinceMs), 0);
                }
                return;
            }
            lastTitleTrack = title;
            int cut = title.indexOf(" - ");
            String artist = title.substring(0, cut).trim();
            String song = title.substring(cut + 3).trim();
            state = new State(title, "Loading lyrics...", new long[0], new String[0], false, now, 0, 0);
            fetch(title, artist, song, now);
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
                publish(track, "", t, lines.toArray(new String[0]), true, startedAt);
                return;
            }
            String plain = str(json.get("plainLyrics"));
            if (plain != null && !plain.isBlank()) {
                publish(track, "", new long[0], plain.split("\n"), false, startedAt);
            } else {
                publish(track, "Instrumental / no lyrics", new long[0], new String[0], false, startedAt);
            }
        } catch (Exception e) {
            publish(track, "Lyrics unavailable (offline?)", new long[0], new String[0], false, startedAt);
        }
    }

    /** Publishes lyrics only if the same track is still current, keeping its clock. */
    private void publish(String track, String status, long[] times, String[] lines, boolean synced, long startedAt) {
        State s = state;
        if (track.equals(s.track)) {
            state = new State(track, status, times, lines, synced, s.startedAtMs, s.pausedTotalMs, s.pausedSinceMs);
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
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void onHudRender(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) {
            return;
        }
        State s = state;
        String current;
        String next = null;
        if (s.track == null || !s.status.isEmpty() || s.lines.length == 0) {
            if (s.track == null && s.status.startsWith("Waiting")) {
                return;
            }
            current = s.status.isEmpty() ? "" : s.status;
        } else if (s.synced) {
            long now = s.pausedSinceMs != 0 ? s.pausedSinceMs : System.currentTimeMillis();
            long pos = now - s.startedAtMs - s.pausedTotalMs + offsetMs.get().longValue();
            int idx = lineAt(s.times, pos);
            current = idx < 0 ? "♪" : s.lines[idx];
            if (idx + 1 < s.lines.length) {
                next = s.lines[idx + 1];
            }
        } else {
            current = "(unsynced) " + s.lines[0];
        }
        float sc = scale.get().floatValue();
        float sw = mc.getWindow().getScaledWidth() / sc;
        float sh = mc.getWindow().getScaledHeight() / sc;
        float cx = sw / 2f;
        float y = (sh - 40) * yPct.get().floatValue() / 100f;

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(sc, sc);
        Ui.beginFrame(sc);
        // Vanilla text renderer for the lyric text: full Unicode (any language, ♪),
        // which the Ui atlas font does not cover.
        var tr = mc.textRenderer;
        boolean withNext = next != null && showNext.get();
        String track = showTrack.get() && s.track != null ? "♫ " + s.track : null;
        int w = tr.getWidth(current);
        if (withNext) {
            w = Math.max(w, tr.getWidth(next));
        }
        if (track != null) {
            w = Math.max(w, tr.getWidth(track));
        }
        w += 20;
        float h = 6 + (track != null ? 11 : 0) + 11 + (withNext ? 11 : 0) + 3;
        Ui.shadow(ctx, cx - w / 2f, y, w, h, 7, 6, 0x55000000);
        Ui.roundRect(ctx, cx - w / 2f, y, w, h, 7, 0xB0101319);
        int ly = (int) y + 6;
        int icx = (int) cx;
        if (track != null) {
            ctx.drawCenteredTextWithShadow(tr, track, icx, ly, 0xFF1ED760);
            ly += 11;
        }
        ctx.drawCenteredTextWithShadow(tr, current, icx, ly, 0xFFFFFFFF);
        ly += 11;
        if (withNext) {
            ctx.drawCenteredTextWithShadow(tr, next, icx, ly, 0xFF8F9AAD);
        }
        ctx.getMatrices().popMatrix();
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
