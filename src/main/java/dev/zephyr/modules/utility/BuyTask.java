package dev.zephyr.modules.utility;

/**
 * Buys one listing found by a {@link MarketScan}: goes back to its page, asks you, clicks
 * it, confirms, and waits for the server's chat line. Free of game classes.
 */
public final class BuyTask implements AuctionRoutine.Task<BuyTask.Result> {

    /** {@code entryId} is -1 when nothing was bought. */
    public record Result(long entryId, String message) {
        public boolean bought() {
            return entryId >= 0;
        }
    }

    private enum State { FIND, ASK, CONFIRM, REPLY }

    private final AuctionGame game;
    private final AuctionHost host;
    private final AuctionSetup setup;
    private final MarketScan.Offer offer;
    private final String owner;
    private final AuctionPager pager;
    private final String what;
    private State state = State.FIND;
    private long since;
    private long confirmSeen = -1;
    private int slot = -1;
    private int listingWindow;
    private boolean manual;
    private Boolean answer;
    private AuctionLedger.Expected expected;
    private Result result;
    private String status = "";

    public BuyTask(AuctionGame game, AuctionHost host, AuctionSetup setup, AuctionGame.Item item,
                   MarketScan.Offer offer, String owner) {
        this.game = game;
        this.host = host;
        this.setup = setup;
        this.offer = offer;
        this.owner = owner;
        this.pager = new AuctionPager(game, setup, MarketScan.search(item.name()));
        this.what = offer.item().count() + "x " + item.name() + " for $"
                + PriceText.shorten(offer.price().value());
    }

    public String status() {
        return status;
    }

    public String question() {
        return state == State.ASK ? "Buy " + what + "?" : null;
    }

    public int slot() {
        return state == State.ASK ? slot : -1;
    }

    public void answer(boolean yes) {
        if (state == State.ASK) {
            answer = yes;
        }
    }

    public boolean awaitingManualConfirm() {
        return state == State.REPLY && manual;
    }

    public void bought(AuctionLedger.Purchase purchase) {
        if (expected != null && purchase.expected() != null
                && (purchase.expected() == expected || purchase.entry().name.equalsIgnoreCase(expected.name))) {
            result = new Result(purchase.entry().id, "bought " + what);
        }
    }

    @Override
    public Result tick(long now) {
        if (result != null) {
            return result;
        }
        return switch (state) {
            case FIND -> find(now);
            case ASK -> ask(now);
            case CONFIRM -> confirm(now);
            case REPLY -> now - since > (manual ? setup.promptTicks + setup.replyTicks : setup.replyTicks)
                    ? failed("no purchase seen in chat for " + what) : null;
        };
    }

    private Result find(long now) {
        AuctionGame.Window w = pager.tick(now);
        if (pager.failed()) {
            return failed(pager.problem());
        }
        if (w == null) {
            return null;
        }
        if (pager.page() < offer.page()) {
            return pager.next(now) ? null : failed("the page with " + what + " is gone");
        }
        slot = locate(w);
        if (slot < 0) {
            return failed("listing is gone: " + what);
        }
        String no = refusal();
        if (no != null) {
            return failed(no);
        }
        if (setup.askFirst) {
            status = "buy " + what + "?";
            enter(State.ASK, now);
            return null;
        }
        return click(w, now);
    }

    private Result ask(long now) {
        AuctionGame.Window w = game.window();
        if (w == null || locate(w) != slot) {
            return failed("listing changed or window closed, not bought");
        }
        if (now - since < 8) {
            answer = null; // a key already on its way down does not answer
            return null;
        }
        if (Boolean.FALSE.equals(answer) || now - since > setup.promptTicks) {
            return failed("skipped " + what);
        }
        if (answer == null) {
            return null;
        }
        String no = refusal();
        return no != null ? failed(no) : click(w, now);
    }

    private String refusal() {
        if (game.inventory().stacks().size() >= 36) {
            return "inventory is full";
        }
        return host.limit(setup, offer.price().high());
    }

    private Result click(AuctionGame.Window w, long now) {
        listingWindow = w.id();
        host.listingClicked(w);
        double price = offer.price().value();
        manual = !setup.oneClick && !host.confirmButton().set();
        if (setup.oneClick) {
            expect(price, true);
        } else if (manual) {
            expect(price, false);
        }
        game.click(slot, 0, AuctionGame.Press.PICKUP);
        status = manual ? "click confirm yourself (it will be remembered)" : "buying " + what;
        enter(expected != null ? State.REPLY : State.CONFIRM, now);
        return null;
    }

    private void expect(double price, boolean sent) {
        int ticks = sent ? setup.replyTicks : setup.promptTicks + setup.replyTicks;
        expected = host.expect(offer.item(), price, owner, sent, ticks);
        if (sent) {
            host.sent(price);
        }
    }

    private Result confirm(long now) {
        if (now - since > setup.windowTicks) {
            return failed("no confirm window matched the learned button (/zephyr ah forget to relearn)");
        }
        AuctionGame.Window w = game.window();
        AuctionBuyer.Button button = host.confirmButton();
        if (w == null || w.id() == listingWindow || !button.on(w.view())) {
            confirmSeen = -1;
            return null;
        }
        if (confirmSeen < 0) {
            confirmSeen = now;
        }
        if (now - confirmSeen < setup.confirmDelayTicks) {
            return null;
        }
        String problem = AuctionBuyer.confirmProblem(w.view(), button.slot, offer.item(), offer.price().value(),
                setup.confirmPrice);
        if (problem == null) {
            problem = host.limit(setup, offer.price().high());
        }
        if (problem != null) {
            return failed(problem);
        }
        expect(offer.price().value(), true);
        game.click(button.slot, 0, AuctionGame.Press.PICKUP);
        enter(State.REPLY, now);
        return null;
    }

    /** The slot still holding this exact listing, preferring where it was; -1 when gone. */
    private int locate(AuctionGame.Window w) {
        AuctionDeals.SlotView there = w.at(offer.slot());
        if (there != null && same(there)) {
            return there.slot();
        }
        return w.slots().stream().filter(s -> s.slot() < setup.listingSlots && same(s))
                .mapToInt(AuctionDeals.SlotView::slot).findFirst().orElse(-1);
    }

    private boolean same(AuctionDeals.SlotView s) {
        return s.key().equals(offer.item().key()) && s.count() == offer.item().count()
                && PriceText.price(s.lore()) == offer.price().value();
    }

    private Result failed(String why) {
        status = why;
        result = new Result(-1, why);
        return result;
    }

    private void enter(State next, long now) {
        state = next;
        since = now;
    }
}
