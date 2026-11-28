package dev.zephyr.modules.utility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads every listing of one item with {@code /ah <item name>}, page by page. The result
 * is complete only when the last page was reached. Free of game classes.
 */
public final class MarketScan implements AuctionRoutine.Task<MarketScan.Market> {

    public record Offer(int page, int slot, AuctionDeals.SlotView item, PriceText.Amount price) {
        public double lowPerItem() {
            return price.low() / item.count();
        }
    }

    public record Market(AuctionGame.Item item, List<Offer> offers, boolean complete, String problem) {
        /** The cheapest per item, or null. */
        public Offer cheapest() {
            return offers.stream().min(Comparator.comparingDouble(Offer::lowPerItem)
                    .thenComparingDouble(o -> o.price().high())).orElse(null);
        }

        /** True when the listings did not fit in the page limit. */
        public boolean tooBig() {
            return !complete && problem != null && problem.startsWith(TOO_BIG);
        }

        public Market without(Offer gone) {
            List<Offer> rest = new ArrayList<>(offers);
            rest.remove(gone);
            return new Market(item, rest, complete, problem);
        }
    }

    private static final String TOO_BIG = "more than ";

    private final AuctionSetup setup;
    private final AuctionGame.Item item;
    private final AuctionPager pager;
    private final List<Offer> offers = new ArrayList<>();
    private int read;

    public MarketScan(AuctionGame game, AuctionSetup setup, AuctionGame.Item item) {
        this.setup = setup;
        this.item = item;
        this.pager = new AuctionPager(game, setup, search(item.name()));
    }

    public static String search(String name) {
        return "ah " + name.toLowerCase(Locale.ROOT);
    }

    @Override
    public Market tick(long now) {
        AuctionGame.Window w = pager.tick(now);
        if (pager.failed()) {
            return done(false, pager.problem());
        }
        if (w == null) {
            return null;
        }
        if (read < pager.page()) {
            read = pager.page();
            for (AuctionDeals.SlotView s : w.slots()) {
                PriceText.Amount price = PriceText.read(s.lore());
                // Only the exact same item counts: same id, name, enchantments and potion.
                if (s.slot() < setup.listingSlots && s.key().equals(item.key()) && price != null
                        && price.value() > 0 && s.count() > 0) {
                    offers.add(new Offer(read, s.slot(), s, price.precise(setup.priceDecimals)));
                }
            }
        }
        if (setup.cheapestFirst && !offers.isEmpty()) {
            return done(true, null);
        }
        if (read >= setup.checkPages && w.at(setup.nextSlot) != null) {
            return done(false, TOO_BIG + setup.checkPages
                    + " pages of results (raise \"Max pages per price check\")");
        }
        if (pager.next(now)) {
            return null;
        }
        // next() is also false on the tick the pager notices that the last page stayed put.
        return done(pager.problem() == null, pager.problem());
    }

    private Market done(boolean complete, String problem) {
        return new Market(item, offers, complete, problem);
    }
}
