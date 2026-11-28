package dev.zephyr.modules.utility;

/**
 * Sells one ledger entry: reads the current market with {@code /ah <item>}, and only when
 * being the cheapest still makes the profit, lists it with {@code /ah sell}. Free of game
 * classes.
 */
public final class SellTask implements AuctionRoutine.Task<SellTask.Result> {

    public record Result(boolean listed, long price, String message) {
    }

    private final AuctionGame game;
    private final AuctionHost host;
    private final AuctionSetup setup;
    private final AuctionLedger.Entry entry;
    private MarketScan scan;
    private MarketScan recheck;
    private StackTask arrange;
    private String listedMessage;
    private Result result;
    private long price = -1;

    public SellTask(AuctionGame game, AuctionHost host, AuctionSetup setup, AuctionLedger.Entry entry) {
        this.game = game;
        this.host = host;
        this.setup = setup;
        this.entry = entry;
    }

    @Override
    public Result tick(long now) {
        AuctionSeller seller = host.seller();
        if (result != null) {
            // Select the slot you held before, once every window is closed.
            if (!seller.restoring()) {
                return result;
            }
            if (!game.free()) {
                game.close();
                return null;
            }
            act(seller.tick(game.inventory(), null, true, now, host.sellOptions(), host.sellButton()));
            return null;
        }
        if (price < 0) {
            return price(now);
        }
        if (arrange != null) {
            String problem = arrange.tick(now);
            if (problem != null) {
                arrange = null;
                if (problem.isEmpty()) {
                    entry.status = AuctionLedger.Status.SELLING;
                    seller.start(new AuctionSeller.Job(entry.id, entry.name, entry.key, entry.count, price), now);
                } else {
                    finish(false, problem);
                }
            }
            return null;
        }
        if (recheck != null) {
            MarketScan.Market market = recheck.tick(now);
            if (market != null) {
                game.close();
                finish(true, listedMessage + ", " + MarketPrice.standing(market, entry.count, price));
            }
            return null;
        }
        AuctionGame.Window w = game.window();
        act(seller.tick(game.inventory(), w == null ? null : w.view(), game.free(), now, host.sellOptions(),
                host.sellButton()));
        AuctionSeller.Result sold = seller.poll();
        if (sold != null) {
            entry.status = sold.listed() ? AuctionLedger.Status.LISTED : AuctionLedger.Status.BOUGHT;
            entry.listedAt = sold.listed() ? host.millis() : 0;
            host.saleEnded(sold.listed());
            if (sold.listed() && setup.verifyListing) {
                listedMessage = sold.message();
                recheck = new MarketScan(game, setup, new AuctionGame.Item("", entry.name, entry.key));
                game.close();
            } else {
                finish(sold.listed(), sold.message());
            }
        }
        return null;
    }

    private Result price(long now) {
        if (entry.paidHigh <= 0) {
            return finish(false, entry.name + ": what it cost is unknown, not listed");
        }
        if (scan == null) {
            if (entry.key.isEmpty()) {
                return finish(false, entry.name + " was not bought through the flipper, not listed");
            }
            // The search uses the name; the exact key decides which listings count.
            scan = new MarketScan(game, setup, new AuctionGame.Item("", entry.name, entry.key));
        }
        MarketScan.Market market = scan.tick(now);
        if (market == null) {
            return null;
        }
        MarketPrice.Quote quote = MarketPrice.quote(market, entry.count, entry.paidHigh,
                host.ledger().askedPerItem(entry.key, host.millis() - 30 * 60_000L), setup);
        if (quote.price() < 0) {
            return finish(false, quote.why());
        }
        price = quote.price();
        entry.asked = price;
        game.close();
        arrange = new StackTask(game, entry.key, entry.name, entry.count);
        return null;
    }

    private Result finish(boolean listed, String message) {
        entry.note = message;
        host.save();
        result = new Result(listed, price, message);
        // A finished result is returned on the next tick, after the hotbar is restored.
        return null;
    }

    private void act(AuctionSeller.Act act) {
        if (act == null) {
            return;
        }
        switch (act.kind()) {
            case SWAP -> game.click(act.slot(), act.hotbar(), AuctionGame.Press.SWAP);
            case SELECT -> game.select(act.hotbar());
            case COMMAND -> game.command(act.command());
            case CLICK -> game.click(act.slot(), 0, AuctionGame.Press.PICKUP);
        }
    }
}
