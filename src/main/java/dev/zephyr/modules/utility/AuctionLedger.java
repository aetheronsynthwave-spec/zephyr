package dev.zephyr.modules.utility;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
        /** Suggested or chosen sale price for the whole stack; 0 when there is none. */
        public double listAt;
        /** Price it was listed for. */
        public double asked;
        public double soldFor;
        public Status status = Status.BOUGHT;
    }

    /** A purchase waiting for its chat line. */
    public static final class Expected {
        public final String name;
        public final String key;
        public final int count;
        public final double price;
        public final double listAt;
        /** True when the buy click was sent by the flipper, so the money is held. */
        public final boolean sent;
        public long deadline;
        /** Anything to hand back on confirmation (the button you clicked). */
        public Object tag;

        public Expected(String name, String key, int count, double price, double listAt, boolean sent,
                        long deadline) {
            this.name = name;
            this.key = key;
            this.count = count;
            this.price = price;
            this.listAt = listAt;
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
            if (e == null || e.name == null || e.key == null || e.status == null || e.count < 0
                    || !money(e.paid) || !money(e.listAt) || !money(e.asked) || !money(e.soldFor)) {
                throw new IllegalArgumentException("bad ledger entry");
            }
        }
        entries.clear();
        for (Entry e : saved) {
            if (e.status == Status.SELLING) {
                e.status = Status.BOUGHT; // the game closed mid-sale; nothing proves it was listed
            }
            entries.add(e);
            nextId = Math.max(nextId, e.id + 1);
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

    /** A "you bought" line. Always recorded, also for purchases made by hand. */
    public Purchase bought(String name, int count, double price, long now) {
        for (Iterator<Expected> it = expected.iterator(); it.hasNext(); ) {
            Expected x = it.next();
            if (x.name.equalsIgnoreCase(name) && (count < 0 || x.count == count)) {
                it.remove();
                Entry e = add(x.name, x.key, x.count, price, x.listAt, Status.BOUGHT, now);
                return new Purchase(e, x);
            }
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.status == Status.UNCONFIRMED && now - e.time <= LATE && e.name.equalsIgnoreCase(name)
                    && (count < 0 || e.count == count)) {
                double listed = e.paid;
                e.status = Status.BOUGHT;
                e.paid = price;
                Expected late = new Expected(e.name, e.key, e.count, listed, e.listAt, true, 0);
                return new Purchase(e, late);
            }
        }
        return new Purchase(add(name, "", Math.max(1, count), price, 0, Status.BOUGHT, now), null);
    }

    /**
     * A "sold" line: closes the listed entry of that name whose asking price is nearest.
     * With nothing listed under that name, the sale is recorded on its own.
     */
    public Entry sold(String name, int count, double price, long now) {
        Entry best = null;
        for (Entry e : entries) {
            if (e.status == Status.LISTED && e.name.equalsIgnoreCase(name) && (count < 0 || e.count == count)
                    && (best == null || Math.abs(e.asked - price) < Math.abs(best.asked - price))) {
                best = e;
            }
        }
        if (best == null) {
            best = add(name, "", Math.max(1, count), 0, 0, Status.SOLD, now);
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
                    add(x.name, x.key, x.count, x.price, x.listAt, Status.UNCONFIRMED, now);
                    changed = true;
                }
            }
        }
        return changed;
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

    private Entry add(String name, String key, int count, double paid, double listAt, Status status, long now) {
        Entry e = new Entry();
        e.id = nextId++;
        e.time = now;
        e.name = name;
        e.key = key;
        e.count = count;
        e.paid = paid;
        e.listAt = listAt;
        e.status = status;
        entries.add(e);
        while (entries.size() > LIMIT) {
            Entry oldest = entries.stream().filter(x -> x.status == Status.SOLD).findFirst().orElse(entries.get(0));
            entries.remove(oldest);
        }
        return e;
    }
}
