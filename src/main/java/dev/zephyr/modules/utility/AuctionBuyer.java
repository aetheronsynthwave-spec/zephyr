package dev.zephyr.modules.utility;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides, once per tick, which auction slot to click. A purchase is: pick the best deal,
 * ask you (unless askFirst is off), click it, wait for the confirm window, check it shows
 * the same item and price, click confirm once. With no learned confirm button you click it
 * yourself. Anything that does not match ends the attempt with no further click. Free of
 * game classes.
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

        /** Numbers in titles (page numbers, prices) are not compared. */
        public static String normalize(String title) {
            return title.replaceAll("[0-9][0-9.,]*[kKmMbBtT]?", "#");
        }

        boolean on(ScreenView view) {
            return set() && normalize(view.title()).equals(normalize(title)) && view.slots().stream()
                    .anyMatch(s -> s.slot() == slot && s.itemId().equals(itemId));
        }
    }

    public static final class Options {
        public boolean autoBuy = true;
        public boolean askFirst = true;
        /** The server buys on the first click: no confirm window. */
        public boolean oneClick = false;
        public boolean confirmPrice = true;
        public boolean refresh = false;
        /** A sent buy still has no chat reply: start nothing new. */
        public boolean awaitingReply = false;
        /** Spending limits. Zero is no limit when asking first, and blocks buying otherwise. */
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
        public int promptTicks = 200;
        /** A key press this soon after a question appears is ignored. */
        public int armTicks = 8;
        /** Part of the browse window title; blank accepts "auction" or "ah". */
        public String auctionTitle = "";
    }

    public enum Kind { LISTING, CONFIRM, REFRESH }

    /** {@code buys} is set on the click that spends the money. */
    public record Click(int slot, Kind kind, AuctionDeals.Deal buys) {
    }

    private enum State { SCAN, PROMPT, AWAIT_CONFIRM, COOLDOWN }

    private final AuctionDeals deals = new AuctionDeals();
    /** Listing identity -> tick it was last tried or declined. */
    private final Map<String, Long> attempted = new HashMap<>();
    private State state = State.SCAN;
    private long stateSince;
    private long stableSince;
    private long confirmSeen = -1;
    private String pageSignature = "";
    private AuctionDeals.Deal pending;
    private boolean manual;
    private Boolean answer;
    private List<AuctionDeals.Deal> marks = List.of();
    private double committed;
    private int buys;
    private String status = "waiting for an auction window";

    public List<AuctionDeals.Deal> marks() {
        return marks;
    }

    /** Money spent or held for sent buy clicks, counted against the budget. */
    public double committed() {
        return committed;
    }

    public int buys() {
        return buys;
    }

    public String status() {
        return status;
    }

    /** The deal you are being asked about, or null. */
    public AuctionDeals.Deal question() {
        return state == State.PROMPT ? pending : null;
    }

    /** The deal being bought: clicked, waiting for the confirm window. */
    public AuctionDeals.Deal opening() {
        return state == State.AWAIT_CONFIRM ? pending : null;
    }

    /** True while the confirm window is yours to click. */
    public boolean awaitingManualConfirm() {
        return state == State.AWAIT_CONFIRM && manual;
    }

    public void answer(boolean yes) {
        if (state == State.PROMPT) {
            answer = yes;
        }
    }

    /** The chat line confirmed a purchase you finished by hand. */
    public void manualBought(double paid, long now) {
        if (awaitingManualConfirm()) {
            committed += paid;
            buys++;
            end(now, "bought " + pending.view().name() + " for " + PriceText.shorten(paid));
        }
    }

    /** The chat line priced a sent buy differently from the listing. */
    public void adjust(double listed, double paid) {
        committed = Math.max(0, committed + paid - listed);
    }

    /** Drops the purchase in progress; spending totals and price history stay. */
    public void abort() {
        state = State.SCAN;
        pending = null;
        answer = null;
        manual = false;
        confirmSeen = -1;
        pageSignature = "";
        marks = List.of();
    }

    /** New session: totals, history and tried listings. */
    public void resetSession() {
        abort();
        deals.clear();
        attempted.clear();
        committed = 0;
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
            if (state == State.PROMPT) {
                end(now, "window closed, not bought");
            }
            marks = List.of();
            pageSignature = "";
            return null;
        }
        marks = deals.evaluate(view.slots(), f, rules, now);
        if (state == State.PROMPT) {
            return prompt(now, o, confirm);
        }
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
        if (o.awaitingReply) {
            status = "waiting for the server's reply";
            return null;
        }
        attempted.values().removeIf(t -> now - t >= o.retryTicks);
        for (AuctionDeals.Deal d : marks) {
            if (overLimit(o, d) || attempted.containsKey(d.identity())) {
                continue;
            }
            if (now - stableSince < (long) o.settleTicks + o.buyDelayTicks) {
                return null; // wait for the best one rather than taking a lesser one
            }
            pending = d;
            if (o.askFirst) {
                answer = null;
                enter(State.PROMPT, now);
                status = "buy " + d.view().count() + "x " + d.view().name() + " for "
                        + PriceText.shorten(d.price()) + "?";
                return null;
            }
            return open(now, o, confirm);
        }
        status = marks.isEmpty() ? "no deal on this page" : "deals shown are over your limits or already seen";
        if (o.refresh && refresh.on(view) && now - stableSince >= o.refreshTicks) {
            stableSince = now;
            return new Click(refresh.slot, Kind.REFRESH, null);
        }
        return null;
    }

    private Click prompt(long now, Options o, Button confirm) {
        AuctionDeals.Deal asked = pending;
        // The very same listing must still be in the very same slot.
        boolean there = marks.stream().anyMatch(d -> d.identity().equals(asked.identity())
                && d.view().slot() == asked.view().slot() && d.price() == asked.price());
        if (!there) {
            return end(now, "listing changed, not bought");
        }
        if (now - stateSince < o.armTicks) {
            answer = null;
            return null;
        }
        if (Boolean.FALSE.equals(answer) || now - stateSince > o.promptTicks) {
            attempted.put(asked.identity(), now);
            return end(now, "skipped " + asked.view().name());
        }
        if (!Boolean.TRUE.equals(answer)) {
            return null;
        }
        answer = null;
        if (blocked(o, confirm) != null || overLimit(o, asked)) {
            return end(now, "limits changed, not bought");
        }
        return open(now, o, confirm);
    }

    /** Clicks the listing in {@code pending}. */
    private Click open(long now, Options o, Button confirm) {
        AuctionDeals.Deal d = pending;
        attempted.put(d.identity(), now);
        if (o.oneClick) {
            send(d);
            pending = null;
            enter(State.COOLDOWN, now);
            return new Click(d.view().slot(), Kind.LISTING, d);
        }
        manual = !confirm.set();
        confirmSeen = -1;
        enter(State.AWAIT_CONFIRM, now);
        status = manual ? "click confirm yourself (it will be remembered)"
                : "opening " + d.view().name() + " for " + PriceText.shorten(d.price());
        return new Click(d.view().slot(), Kind.LISTING, null);
    }

    private Click awaitConfirm(ScreenView view, long now, Options o, Button confirm) {
        if (manual) {
            if (now - stateSince > o.promptTicks) {
                return end(now, "no purchase seen in chat");
            }
            return null;
        }
        if (now - stateSince > o.timeoutTicks) {
            return end(now, "no confirm window: listing gone, or run /zephyr ah forget");
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
        if (blocked(o, confirm) != null || overLimit(o, pending)) {
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
            return end(now, o.confirmPrice ? "confirm window does not show that item at that price, not confirmed"
                    : "confirm window does not show that item, not confirmed");
        }
        // A price written on the confirm button itself must agree too.
        for (AuctionDeals.SlotView s : view.slots()) {
            double shown = s.slot() == confirm.slot ? PriceText.price(s.lore()) : -1;
            if (shown > 0 && shown != pending.price()) {
                return end(now, "confirm button shows another price, not confirmed");
            }
        }
        AuctionDeals.Deal d = pending;
        send(d);
        pending = null;
        enter(State.COOLDOWN, now);
        return new Click(confirm.slot, Kind.CONFIRM, d);
    }

    private boolean overLimit(Options o, AuctionDeals.Deal d) {
        return o.maxPrice > 0 && d.price() > o.maxPrice || o.budget > 0 && committed + d.price() > o.budget;
    }

    private String blocked(Options o, Button confirm) {
        if (!o.autoBuy) {
            return "autoBuy is off";
        }
        if (!o.askFirst && !o.oneClick && !confirm.set()) {
            return "confirm button not learned: buy once with askFirst on";
        }
        if (!o.askFirst && (!(o.maxPrice > 0) || !(o.budget > 0))) {
            return "set maxPrice and budget, or turn askFirst on";
        }
        if (buys >= o.maxBuys) {
            return "maxBuys reached (/zephyr ah reset)";
        }
        return null;
    }

    /** Holds the money when the buying click is sent; the chat line confirms it. */
    private void send(AuctionDeals.Deal d) {
        committed += d.price();
        buys++;
        status = "sent buy: " + d.view().name() + " for " + PriceText.shorten(d.price());
    }

    private Click end(long now, String why) {
        status = why;
        pending = null;
        answer = null;
        manual = false;
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
