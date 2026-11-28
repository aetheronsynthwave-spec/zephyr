package dev.zephyr.modules.utility;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides, once per tick, which auction slot to click. A purchase is: click the best deal,
 * wait for the learned confirm window, check it shows the same item and price, click confirm
 * once. Anything that does not match ends the attempt with no further click. Free of game
 * classes.
 */
public final class AuctionBuyer {

    public record ScreenView(String title, List<AuctionDeals.SlotView> slots) {
    }

    /** A button position recorded from your own click. Unset while {@code slot} is -1. */
    public static final class Button {
        public String title = "";
        public int slot = -1;
        public String itemId = "";

        public boolean set() {
            return slot >= 0 && !title.isEmpty() && !itemId.isEmpty();
        }

        boolean on(ScreenView view) {
            return set() && view.title().equals(title) && view.slots().stream()
                    .anyMatch(s -> s.slot() == slot && s.itemId().equals(itemId));
        }
    }

    public static final class Options {
        public boolean autoBuy = true;
        /** The server buys on the first click: no confirm window. */
        public boolean oneClick = false;
        public boolean confirmPrice = true;
        public boolean refresh = false;
        /** Spending limits. Zero means unset, and nothing is bought. */
        public double maxPrice = 0;
        public double budget = 0;
        public int maxBuys = 10;
        public int settleTicks = 4;
        public int buyDelayTicks = 0;
        public int confirmDelayTicks = 2;
        public int timeoutTicks = 60;
        public int cooldownTicks = 20;
        public int retryTicks = 1200;
        public int refreshTicks = 40;
        /** Part of the browse window title; blank accepts "auction" or "ah". */
        public String auctionTitle = "";
    }

    public enum Kind { LISTING, CONFIRM, REFRESH }

    public record Click(int slot, Kind kind) {
    }

    private enum State { SCAN, AWAIT_CONFIRM, COOLDOWN }

    private final AuctionDeals deals = new AuctionDeals();
    /** Listing identity -> tick it was last tried. */
    private final Map<String, Long> attempted = new HashMap<>();
    private State state = State.SCAN;
    private long stateSince;
    private long stableSince;
    private long confirmSeen = -1;
    private String pageSignature = "";
    private AuctionDeals.Deal pending;
    private List<AuctionDeals.Deal> marks = List.of();
    private double spent;
    private int buys;
    private String status = "waiting for an auction window";

    public List<AuctionDeals.Deal> marks() {
        return marks;
    }

    public double spent() {
        return spent;
    }

    public int buys() {
        return buys;
    }

    public String status() {
        return status;
    }

    /** Drops the purchase in progress; spending totals and price history stay. */
    public void abort() {
        state = State.SCAN;
        pending = null;
        confirmSeen = -1;
        pageSignature = "";
        marks = List.of();
    }

    /** New session: totals, history and tried listings. */
    public void resetSession() {
        abort();
        deals.clear();
        attempted.clear();
        spent = 0;
        buys = 0;
        status = "session reset";
    }

    public static boolean isAuctionTitle(String title, String custom) {
        String t = title.toLowerCase(Locale.ROOT);
        if (custom != null && !custom.isBlank()) {
            return t.contains(custom.trim().toLowerCase(Locale.ROOT));
        }
        return t.contains("auction") || t.startsWith("ah ") || t.equals("ah");
    }

    /** @return the click to send this tick, or null. {@code view} is null with no container open. */
    public Click tick(ScreenView view, long now, Options o, AuctionDeals.Filter f, AuctionDeals.Rules rules,
                      Button confirm, Button refresh) {
        if (state == State.AWAIT_CONFIRM) {
            return awaitConfirm(view, now, o, confirm);
        }
        if (view == null || confirm.on(view) || !isAuctionTitle(view.title(), o.auctionTitle)) {
            // A confirm window with no purchase of ours pending is yours: never touched.
            marks = List.of();
            pageSignature = "";
            return null;
        }
        marks = deals.evaluate(view.slots(), f, rules, now);
        String signature = signature(view);
        if (!signature.equals(pageSignature)) {
            pageSignature = signature;
            stableSince = now;
        }
        if (state == State.COOLDOWN) {
            if (now - stateSince < o.cooldownTicks) {
                return null;
            }
            state = State.SCAN;
        }
        if (now - stableSince < o.settleTicks) {
            return null; // the page is still filling in: the best deal may not be here yet
        }
        String blocked = blocked(o, confirm);
        if (blocked != null) {
            status = blocked;
            return null;
        }
        attempted.values().removeIf(t -> now - t >= o.retryTicks);
        for (AuctionDeals.Deal d : marks) {
            if (d.price() > o.maxPrice || spent + d.price() > o.budget || attempted.containsKey(d.identity())) {
                continue;
            }
            if (now - stableSince < (long) o.settleTicks + o.buyDelayTicks) {
                return null; // wait for the best one rather than taking a lesser one
            }
            attempted.put(d.identity(), now);
            if (o.oneClick) {
                charge(d);
                enter(State.COOLDOWN, now);
            } else {
                pending = d;
                confirmSeen = -1;
                enter(State.AWAIT_CONFIRM, now);
                status = "opening " + d.view().name() + " for " + PriceText.shorten(d.price());
            }
            return new Click(d.view().slot(), Kind.LISTING);
        }
        status = marks.isEmpty() ? "no deal on this page" : "deals shown are over your limits or already tried";
        if (o.refresh && refresh.on(view) && now - stableSince >= o.refreshTicks) {
            stableSince = now;
            return new Click(refresh.slot, Kind.REFRESH);
        }
        return null;
    }

    private Click awaitConfirm(ScreenView view, long now, Options o, Button confirm) {
        if (now - stateSince > o.timeoutTicks) {
            return end(now, "no confirm window: listing gone, or learn the button again");
        }
        if (view == null || !confirm.on(view)) {
            confirmSeen = -1;
            return null;
        }
        if (confirmSeen < 0) {
            confirmSeen = now;
        }
        if (now - confirmSeen < o.confirmDelayTicks) {
            return null;
        }
        if (blocked(o, confirm) != null || pending.price() > o.maxPrice || spent + pending.price() > o.budget) {
            return end(now, "limits changed, not confirmed");
        }
        boolean itemShown = false;
        for (AuctionDeals.SlotView s : view.slots()) {
            if (s.slot() == confirm.slot) {
                continue;
            }
            double shown = PriceText.price(s.lore());
            boolean same = s.key().equals(pending.view().key()) && s.count() == pending.view().count();
            if (shown > 0 && shown != pending.price() && same) {
                return end(now, "price changed to " + PriceText.shorten(shown) + ", not confirmed");
            }
            itemShown |= same && (!o.confirmPrice || shown == pending.price());
        }
        if (!itemShown) {
            return end(now, o.confirmPrice ? "confirm window does not show that item at that price"
                    : "confirm window does not show that item");
        }
        // A price written on the confirm button itself must agree too.
        for (AuctionDeals.SlotView s : view.slots()) {
            double shown = s.slot() == confirm.slot ? PriceText.price(s.lore()) : -1;
            if (shown > 0 && shown != pending.price()) {
                return end(now, "confirm button shows another price, not confirmed");
            }
        }
        charge(pending);
        pending = null;
        enter(State.COOLDOWN, now);
        return new Click(confirm.slot, Kind.CONFIRM);
    }

    private String blocked(Options o, Button confirm) {
        if (!o.autoBuy) {
            return "autoBuy is off";
        }
        if (!o.oneClick && !confirm.set()) {
            return "confirm button unset: run /zephyr ah learn confirm";
        }
        if (!(o.maxPrice > 0) || !(o.budget > 0)) {
            return "set maxPrice and budget above 0";
        }
        if (buys >= o.maxBuys) {
            return "maxBuys reached (/zephyr ah reset)";
        }
        return null;
    }

    /** Counted when the buying click is sent: the server's answer is not read. */
    private void charge(AuctionDeals.Deal d) {
        spent += d.price();
        buys++;
        status = "sent buy: " + d.view().name() + " for " + PriceText.shorten(d.price());
    }

    private Click end(long now, String why) {
        status = why;
        pending = null;
        enter(State.COOLDOWN, now);
        return null;
    }

    private void enter(State next, long now) {
        state = next;
        stateSince = now;
    }

    private static String signature(ScreenView view) {
        StringBuilder out = new StringBuilder(view.title());
        for (AuctionDeals.SlotView s : view.slots()) {
            out.append('\n').append(s.slot()).append(':').append(s.key()).append(':').append(s.count())
                    .append(':').append(PriceText.price(s.lore()));
        }
        return out.toString();
    }
}
