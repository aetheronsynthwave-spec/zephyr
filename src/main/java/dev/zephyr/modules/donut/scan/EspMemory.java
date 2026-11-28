package dev.zephyr.modules.donut.scan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the scanners have seen, kept after the data goes away. Something is only forgotten
 * when two scans in a row miss it while the player is within the verify distance; from
 * further off a server may be masking it, so a miss there proves nothing.
 * Not thread safe: used from the client thread only.
 */
public final class EspMemory {

    private static final int VERSION = 1;
    private static final int MISSES = 2;
    private static final int MAX_FINDS = 30000;
    private static final int MAX_HINTS = 12000;

    /** A remembered block. */
    public static final class Seen {
        public final Pos pos;
        private Mat mat;
        private long time;
        private int misses;
        private boolean live;

        private Seen(Pos pos, Mat mat, long time) {
            this.pos = pos;
            this.mat = mat;
            this.time = time;
        }

        public Mat mat() {
            return mat;
        }

        /** Present in the latest scan of its chunk, which is still loaded. */
        public boolean live() {
            return live;
        }
    }

    /** A remembered trace. */
    public static final class Hint {
        private Trace trace;
        private long time;
        private int misses;
        private boolean live;

        private Hint(Trace trace, long time) {
            this.trace = trace;
            this.time = time;
        }

        public Trace trace() {
            return trace;
        }

        public boolean live() {
            return live;
        }
    }

    private final Map<Long, Map<Pos, Seen>> finds = new HashMap<>();
    private final Map<Long, EnumMap<Trace.Kind, Hint>> hints = new HashMap<>();
    private int findCount;
    private int hintCount;
    private boolean dirty;

    public int findCount() {
        return findCount;
    }

    public int hintCount() {
        return hintCount;
    }

    /** Changed since the last {@link #toJson()}. */
    public boolean dirty() {
        return dirty;
    }

    public void clear() {
        finds.clear();
        hints.clear();
        findCount = 0;
        hintCount = 0;
        dirty = true;
    }

    /** Folds one chunk scan in. {@code verify} is in blocks. */
    public void merge(ChunkScan.Result result, Pos viewer, long now, double verify) {
        long key = Pos.chunkKey(result.chunkX(), result.chunkZ());
        double near = verify * verify;
        Map<Pos, Seen> chunk = finds.computeIfAbsent(key, k -> new HashMap<>());
        chunk.values().forEach(s -> s.live = false);
        for (ChunkScan.Find find : result.finds()) {
            Seen seen = chunk.get(find.pos());
            if (seen == null) {
                seen = new Seen(find.pos(), find.mat(), now);
                chunk.put(find.pos(), seen);
                findCount++;
                dirty = true;
            } else if (seen.mat != find.mat()) {
                seen.mat = find.mat();
                dirty = true;
            }
            seen.time = now;
            seen.misses = 0;
            seen.live = true;
        }
        Iterator<Seen> blocks = chunk.values().iterator();
        while (blocks.hasNext()) {
            Seen seen = blocks.next();
            if (!seen.live && seen.pos.distanceSq(viewer) <= near && ++seen.misses >= MISSES) {
                blocks.remove();
                findCount--;
                dirty = true;
            }
        }
        if (chunk.isEmpty()) {
            finds.remove(key);
        }

        EnumMap<Trace.Kind, Hint> here = hints.computeIfAbsent(key, k -> new EnumMap<>(Trace.Kind.class));
        for (Trace.Kind kind : result.checked()) {
            Hint hint = here.get(kind);
            if (hint != null) {
                hint.live = false;
            }
        }
        for (Trace trace : result.traces()) {
            put(here, trace, now);
        }
        for (Trace.Kind kind : result.checked()) {
            Hint hint = here.get(kind);
            if (hint != null && !hint.live && hint.trace.pos().distanceSq(viewer) <= near
                    && ++hint.misses >= MISSES) {
                here.remove(kind);
                hintCount--;
                dirty = true;
            }
        }
        if (here.isEmpty()) {
            hints.remove(key);
        }
        trim();
    }

    private void put(EnumMap<Trace.Kind, Hint> here, Trace trace, long now) {
        Hint hint = here.get(trace.kind());
        if (hint == null) {
            hint = new Hint(trace, now);
            here.put(trace.kind(), hint);
            hintCount++;
            dirty = true;
        } else if (!hint.trace.equals(trace)) {
            hint.trace = trace;
            dirty = true;
        }
        hint.time = now;
        hint.misses = 0;
        hint.live = true;
    }

    /** The chunk left the client: everything in it is now memory only. */
    public void unload(long key) {
        Map<Pos, Seen> chunk = finds.get(key);
        if (chunk != null) {
            chunk.values().forEach(s -> s.live = false);
        }
        EnumMap<Trace.Kind, Hint> here = hints.get(key);
        if (here != null) {
            here.values().forEach(h -> h.live = false);
        }
    }

    /**
     * Folds in the growth heat. A chunk with young amethyst now in reach is dropped at once;
     * one that merely stopped being a peak is dropped only after being checked up close.
     */
    public void suspects(SuspectHeat.Report report, Set<Long> scanned, Pos viewer, long now, double verify) {
        report.suspects().forEach((key, heat) -> {
            Pos centre = new Pos((Pos.keyX(key) << 4) + 8, 0, (Pos.keyZ(key) << 4) + 8);
            put(hints.computeIfAbsent(key, k -> new EnumMap<>(Trace.Kind.class)),
                    Trace.at(Trace.Kind.GROWTH, centre, heat), now);
        });
        Iterator<Map.Entry<Long, EnumMap<Trace.Kind, Hint>>> chunks = hints.entrySet().iterator();
        while (chunks.hasNext()) {
            Map.Entry<Long, EnumMap<Trace.Kind, Hint>> entry = chunks.next();
            long key = entry.getKey();
            Hint hint = entry.getValue().get(Trace.Kind.GROWTH);
            if (hint == null || report.suspects().containsKey(key)) {
                continue;
            }
            hint.live = false;
            boolean near = scanned.contains(key) && hint.trace.pos().flatSq(viewer) <= verify * verify;
            if (report.ruledOut().contains(key) || near && ++hint.misses >= MISSES) {
                entry.getValue().remove(Trace.Kind.GROWTH);
                hintCount--;
                dirty = true;
                if (entry.getValue().isEmpty()) {
                    chunks.remove();
                }
            }
        }
        trim();
    }

    /** A sound heard at a position; kept until {@link #expireSounds}. */
    public void sound(Trace.Kind kind, Pos pos, long now) {
        EnumMap<Trace.Kind, Hint> here = hints.computeIfAbsent(pos.chunkKey(), k -> new EnumMap<>(Trace.Kind.class));
        Hint old = here.get(kind);
        int heard = old == null ? 1 : old.trace.strength() + 1;
        put(here, Trace.at(kind, pos, heard), now);
        trim();
    }

    public void expireSounds(long now, long life) {
        Iterator<EnumMap<Trace.Kind, Hint>> chunks = hints.values().iterator();
        while (chunks.hasNext()) {
            EnumMap<Trace.Kind, Hint> here = chunks.next();
            for (Trace.Kind kind : new Trace.Kind[]{Trace.Kind.SOUND_AMETHYST, Trace.Kind.SOUND_STORAGE}) {
                Hint hint = here.get(kind);
                if (hint != null && now - hint.time > life) {
                    here.remove(kind);
                    hintCount--;
                    dirty = true;
                }
            }
            if (here.isEmpty()) {
                chunks.remove();
            }
        }
    }

    /** Matching blocks within {@code range} blocks measured flat, nearest first. */
    public List<Seen> finds(Predicate<Mat> wanted, Pos viewer, double range, int limit) {
        List<Seen> out = new ArrayList<>();
        double reach = range * range;
        double chunkReach = (range + 24) * (range + 24);
        finds.forEach((key, chunk) -> {
            Pos centre = new Pos((Pos.keyX(key) << 4) + 8, 0, (Pos.keyZ(key) << 4) + 8);
            if (centre.flatSq(viewer) > chunkReach) {
                return;
            }
            for (Seen seen : chunk.values()) {
                if (wanted.test(seen.mat) && seen.pos.flatSq(viewer) <= reach) {
                    out.add(seen);
                }
            }
        });
        out.sort(Comparator.comparingDouble(s -> s.pos.distanceSq(viewer)));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Matching traces within {@code range} blocks measured flat, nearest first. */
    public List<Hint> hints(Set<Trace.Kind> wanted, Pos viewer, double range, int limit) {
        List<Hint> out = new ArrayList<>();
        double reach = range * range;
        for (EnumMap<Trace.Kind, Hint> here : hints.values()) {
            for (Hint hint : here.values()) {
                if (wanted.contains(hint.trace.kind()) && hint.trace.pos().flatSq(viewer) <= reach) {
                    out.add(hint);
                }
            }
        }
        out.sort(Comparator.comparingDouble(h -> h.trace.pos().flatSq(viewer)));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Drops the oldest entries once a tenth over the limit. */
    private void trim() {
        if (findCount > MAX_FINDS + MAX_FINDS / 10) {
            List<Long> times = new ArrayList<>();
            finds.values().forEach(c -> c.values().forEach(s -> times.add(s.time)));
            times.sort(null);
            long cut = times.get(findCount - MAX_FINDS - 1);
            Iterator<Map<Pos, Seen>> chunks = finds.values().iterator();
            while (chunks.hasNext()) {
                Map<Pos, Seen> chunk = chunks.next();
                Iterator<Seen> blocks = chunk.values().iterator();
                while (blocks.hasNext()) {
                    Seen seen = blocks.next();
                    if (seen.time <= cut && findCount > MAX_FINDS) {
                        blocks.remove();
                        findCount--;
                    }
                }
                if (chunk.isEmpty()) {
                    chunks.remove();
                }
            }
            dirty = true;
        }
        if (hintCount > MAX_HINTS + MAX_HINTS / 10) {
            List<Long> times = new ArrayList<>();
            hints.values().forEach(c -> c.values().forEach(h -> times.add(h.time)));
            times.sort(null);
            long cut = times.get(hintCount - MAX_HINTS - 1);
            Iterator<EnumMap<Trace.Kind, Hint>> chunks = hints.values().iterator();
            while (chunks.hasNext()) {
                EnumMap<Trace.Kind, Hint> here = chunks.next();
                Iterator<Hint> each = here.values().iterator();
                while (each.hasNext()) {
                    Hint hint = each.next();
                    if (hint.time <= cut && hintCount > MAX_HINTS) {
                        each.remove();
                        hintCount--;
                    }
                }
                if (here.isEmpty()) {
                    chunks.remove();
                }
            }
            dirty = true;
        }
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray blocks = new JsonArray();
        finds.values().forEach(chunk -> chunk.values().forEach(seen -> {
            JsonArray row = row(seen.pos);
            row.add(seen.mat.name());
            row.add(seen.time);
            blocks.add(row);
        }));
        root.add("blocks", blocks);
        JsonArray traces = new JsonArray();
        hints.values().forEach(here -> here.values().forEach(hint -> {
            JsonObject o = new JsonObject();
            o.addProperty("kind", hint.trace.kind().name());
            o.add("at", row(hint.trace.pos()));
            o.addProperty("strength", hint.trace.strength());
            o.addProperty("mats", hint.trace.mats());
            o.addProperty("time", hint.time);
            JsonArray cells = new JsonArray();
            hint.trace.cells().forEach(c -> cells.add(row(c)));
            o.add("cells", cells);
            traces.add(o);
        }));
        root.add("traces", traces);
        dirty = false;
        return root.toString();
    }

    private static JsonArray row(Pos pos) {
        JsonArray row = new JsonArray();
        row.add(pos.x());
        row.add(pos.y());
        row.add(pos.z());
        return row;
    }

    private static Pos pos(JsonArray row) {
        int x = row.get(0).getAsInt();
        int y = row.get(1).getAsInt();
        int z = row.get(2).getAsInt();
        if (Math.abs((long) x) > 30_000_100 || Math.abs((long) z) > 30_000_100 || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("position out of range");
        }
        return new Pos(x, y, z);
    }

    /**
     * Reads a saved memory. A damaged row is skipped.
     *
     * @throws IllegalArgumentException when the text is not a memory file of this version
     */
    public static EspMemory fromJson(String json) {
        JsonObject root;
        JsonArray blocks;
        JsonArray traces;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
            if (root.get("version").getAsInt() != VERSION) {
                throw new IllegalArgumentException("unknown memory version");
            }
            blocks = root.getAsJsonArray("blocks");
            traces = root.getAsJsonArray("traces");
            if (blocks == null || traces == null) {
                throw new IllegalArgumentException("lists missing");
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not an ESP memory file: " + e.getMessage(), e);
        }
        EspMemory memory = new EspMemory();
        for (JsonElement element : blocks) {
            try {
                JsonArray row = element.getAsJsonArray();
                Pos at = pos(row);
                Seen seen = new Seen(at, Mat.valueOf(row.get(3).getAsString()), row.get(4).getAsLong());
                if (memory.finds.computeIfAbsent(at.chunkKey(), k -> new HashMap<>()).put(at, seen) == null) {
                    memory.findCount++;
                }
            } catch (RuntimeException skip) {
                memory.dirty = true;
            }
        }
        for (JsonElement element : traces) {
            try {
                JsonObject o = element.getAsJsonObject();
                List<Pos> cells = new ArrayList<>();
                for (JsonElement c : o.getAsJsonArray("cells")) {
                    cells.add(pos(c.getAsJsonArray()));
                }
                Trace trace = new Trace(Trace.Kind.valueOf(o.get("kind").getAsString()),
                        pos(o.getAsJsonArray("at")), o.get("strength").getAsInt(), o.get("mats").getAsLong(), cells);
                if (memory.hints.computeIfAbsent(trace.pos().chunkKey(), k -> new EnumMap<>(Trace.Kind.class))
                        .put(trace.kind(), new Hint(trace, o.get("time").getAsLong())) == null) {
                    memory.hintCount++;
                }
            } catch (RuntimeException skip) {
                memory.dirty = true;
            }
        }
        memory.trim();
        return memory;
    }
}
