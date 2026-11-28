package dev.zephyr.modules.utility;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * What was bought and sold. A buy click is "expected" until the server's chat line
 * confirms it. Times are epoch milliseconds. Free of game classes.
 */
public final class AuctionLedger {

    public enum Status { UNCONFIRMED, BOUGHT, SELLING, LISTED, SOLD }

    public static final class Entry {
        public long id;
        public long time;
        public String name = "";
        public String key = "";
        public int count;
        /** What the purchase cost; 0 when no purchase was seen (a sale of your own item). */
        public double paid;
        /** The most it can have cost, given how roughly the price was shown. */
        public double paidHigh;
        /** "flip" or "craft" when a module bought it and may sell it; "" for your own buys. */
        public String owner = "";
        /** Why it is not listed yet. */
        public String note = "";
        /** Price it was listed for. */
        public double asked;
        public long listedAt;
        public double soldFor;
        public Status status = Status.BOUGHT;
    }

    /** A purchase waiting for its chat line. */
    public static final class Expected {
        public final String name;
        public final String key;
        public final int count;
        public final double price;
        public final String owner;
        /** True when the buy click was sent by the flipper, so the money is held. */
        public final boolean sent;
        public long deadline;
        /** Anything to hand back on confirmation (the button you clicked). */
        public Object tag;

        public Expected(String name, String key, int count, double price, String owner, boolean sent,
                        long deadline) {
            this.name = name;
            this.key = key;
            this.count = count;
            this.price = price;
            this.owner = owner;
            this.sent = sent;
            this.deadline = deadline;
        }
    }

    public record Purchase(Entry entry, Expected expected) {
    }

    private static final int LIMIT = 500;
    /** A late chat line may still confirm an unconfirmed entry for this long. */
    private static final long LATE = 5 * 60_000L;

    private final List<Entry> entries = new ArrayList<>();
    private final List<Expected> expected = new ArrayList<>();
    private long nextId = 1;

    /** Oldest first. */
    public List<Entry> entries() {
        return entries;
    }

    /** Replaces the contents with saved entries; rejects anything malformed. */
    public void load(List<Entry> saved) {
        for (Entry e : saved) {
            if (e == null || e.name == null || e.key == null || e.status == null || e.owner == null
                    || e.note == null || e.count < 0 || !money(e.paid) || !money(e.paidHigh) || !money(e.asked)
                    || !money(e.soldFor)) {
                throw new IllegalArgumentException("bad ledger entry");
            }
        }
        entries.clear();
        for (Entry e : saved) {
            if (e.status == Status.SELLING) {
                e.status = Status.BOUGHT; // the game closed mid-sale; nothing proves it was listed
            }
            if (e.paidHigh <= 0) {
                e.paidHigh = e.paid; // saved by an earlier build
            }
            entries.add(e);
            nextId = Math.max(nextId, e.id + 1);
        }
        repair();
    }

    /**
     * Joins the two halves an earlier build saved when it missed a plural chat line: the
     * purchase seen as your own, and the module's click left unconfirmed seconds later.
     */
    private void repair() {
        for (Entry waiting : entries) {
            if (waiting.status != Status.UNCONFIRMED || waiting.owner.isEmpty()) {
                continue;
            }
            Entry seen = entries.stream().filter(e -> e.status == Status.BOUGHT && e.owner.isEmpty()
                    && e.key.isEmpty() && e.count == waiting.count && sameName(e.name, waiting.name)
                    && waiting.time >= e.time && waiting.time - e.time <= 30_000L
                    && samePrice(waiting.paid, e.paid, Math.max(e.paid, e.paidHigh))).findFirst().orElse(null);
            if (seen != null) {
                waiting.status = Status.BOUGHT;
                waiting.paid = seen.paid;
                waiting.paidHigh = seen.paidHigh;
                entries.remove(seen);
                // The list changed under this loop; look again from the start.
                repair();
                return;
            }
        }
    }

    private static boolean money(double d) {
        return Double.isFinite(d) && d >= 0;
    }

    public void expect(Expected e) {
        expected.add(e);
    }

    public boolean awaiting() {
        return !expected.isEmpty();
    }

    /** The newest purchase still waiting, or null. */
    public Expected latestExpected() {
        return expected.isEmpty() ? null : expected.get(expected.size() - 1);
    }

    /** Forgets a purchase that never had its buy click confirmed. */
    public void drop(Expected e) {
        expected.remove(e);
    }

    /**
     * Whether a chat line's item name means this item. The server writes "64 Spruce Logs"
     * for the item "Spruce Log" (DonutSMP chat screenshot dated 2026-11-27); the "es" and
     * "ies" endings are assumptions.
     */
    public static boolean sameName(String chat, String item) {
        String shown = plain(chat);
        String name = plain(item);
        return shown.equals(name) || shown.equals(name + "s") || shown.equals(name + "es")
                || name.endsWith("y") && shown.equals(name.substring(0, name.length() - 1) + "ies");
    }

    private static String plain(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * The purchase a "you bought" line is about: by name, else the only click of ours that
     * has the same count and price, for a name the server words differently.
     */
    private Expected waitingFor(String name, int count, double price, double high) {
        for (Expected x : expected) {
            if (plain(x.name).equals(plain(name)) && (count < 0 || x.count == count)) {
                return x;
            }
        }
        for (Expected x : expected) {
            if (sameName(name, x.name) && (count < 0 || x.count == count)) {
                return x;
            }
        }
        List<Expected> alike = expected.stream()
                .filter(x -> x.sent && x.count == count && samePrice(x.price, price, high)).toList();
        return alike.size() == 1 ? alike.get(0) : null;
    }

    private static boolean samePrice(double listed, double paid, double high) {
        return Math.abs(listed - paid) <= Math.max(0.5, high - paid);
    }

    /** A "you bought" line. Always recorded, also for purchases made by hand. */
    public Purchase bought(String name, int count, double price, double high, long now) {
        Expected match = waitingFor(name, count, price, high);
        if (match != null) {
            expected.remove(match);
            Entry e = add(match.name, match.key, match.count, price, Status.BOUGHT, now);
            e.paidHigh = high;
            e.owner = match.owner;
            return new Purchase(e, match);
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.status == Status.UNCONFIRMED && now - e.time <= LATE && sameName(name, e.name)
                    && (count < 0 || e.count == count)) {
                double listed = e.paid;
                e.status = Status.BOUGHT;
                e.paid = price;
                e.paidHigh = high;
                Expected late = new Expected(e.name, e.key, e.count, listed, e.owner, true, 0);
                return new Purchase(e, late);
            }
        }
        Entry own = add(name, "", Math.max(1, count), price, Status.BOUGHT, now);
        own.paidHigh = high;
        return new Purchase(own, null);
    }

    /**
     * A "sold" line: closes the listed entry of that name whose asking price is nearest.
     * With nothing listed under that name, the sale is recorded on its own.
     */
    public Entry sold(String name, int count, double price, long now) {
        Entry best = null;
        for (Entry e : entries) {
            if (e.status == Status.LISTED && sameName(name, e.name) && (count < 0 || e.count == count)
                    && (best == null || Math.abs(e.asked - price) < Math.abs(best.asked - price))) {
                best = e;
            }
        }
        if (best == null) {
            best = add(name, "", Math.max(1, count), 0, Status.SOLD, now);
        }
        best.status = Status.SOLD;
        best.soldFor = price;
        return best;
    }

    /** Moves expired purchases on: a sent click is kept as unconfirmed, a manual one dropped. */
    public boolean expire(long now) {
        boolean changed = false;
        for (Iterator<Expected> it = expected.iterator(); it.hasNext(); ) {
            Expected x = it.next();
            if (now >= x.deadline) {
                it.remove();
                if (x.sent) {
                    Entry e = add(x.name, x.key, x.count, x.price, Status.UNCONFIRMED, now);
                    e.paidHigh = x.price;
                    e.owner = x.owner;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** A bought stack was crafted into something else; what it cost stays with the result. */
    public void transform(Entry e, String name, String key, int count) {
        e.name = name;
        e.key = key;
        e.count = count;
        e.note = "";
    }

    /** Takes {@code count} off an entry into its own, with its share of the cost. */
    public Entry split(Entry from, int count, long now) {
        if (count >= from.count) {
            return from;
        }
        double share = (double) count / from.count;
        Entry part = add(from.name, from.key, count, from.paid * share, from.status, now);
        part.paidHigh = from.paidHigh * share;
        part.owner = from.owner;
        from.paid -= part.paid;
        from.paidHigh -= part.paidHigh;
        from.count -= count;
        return part;
    }

    /** What you ask per item for this key, in listings made since {@code since}. */
    public List<Double> askedPerItem(String key, long since) {
        return entries.stream()
                .filter(e -> e.status == Status.LISTED && e.key.equals(key) && e.count > 0 && e.listedAt >= since)
                .map(e -> e.asked / e.count).toList();
    }

    public Entry byId(long id) {
        return entries.stream().filter(e -> e.id == id).findFirst().orElse(null);
    }

    public void remove(long id) {
        entries.removeIf(e -> e.id == id);
    }

    public double spent() {
        return entries.stream().filter(e -> e.status != Status.UNCONFIRMED).mapToDouble(e -> e.paid).sum();
    }

    public double unconfirmed() {
        return entries.stream().filter(e -> e.status == Status.UNCONFIRMED).mapToDouble(e -> e.paid).sum();
    }

    public double earned() {
        return entries.stream().filter(e -> e.status == Status.SOLD).mapToDouble(e -> e.soldFor).sum();
    }

    /** Sale minus cost, over sold entries whose purchase was seen. */
    public double profit() {
        return entries.stream().filter(e -> e.status == Status.SOLD && e.paid > 0)
                .mapToDouble(e -> e.soldFor - e.paid).sum();
    }

    private Entry add(String name, String key, int count, double paid, Status status, long now) {
        Entry e = new Entry();
        e.id = nextId++;
        e.time = now;
        e.name = name;
        e.key = key;
        e.count = count;
        e.paid = paid;
        e.status = status;
        entries.add(e);
        // Old history goes first; stock that is still waiting to be sold is never dropped.
        for (Status gone : new Status[]{Status.SOLD, Status.LISTED, Status.UNCONFIRMED}) {
            while (entries.size() > LIMIT) {
                Entry oldest = entries.stream().filter(x -> x.status == gone).findFirst().orElse(null);
                if (oldest == null) {
                    break;
                }
                entries.remove(oldest);
            }
        }
        return e;
    }
}
