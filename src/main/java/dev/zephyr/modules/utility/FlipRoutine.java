package dev.zephyr.modules.utility;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The automatic flip loop: sell what is waiting, read your money, browse /ah page by page,
 * check each promising item's whole market, buy only if it can be relisted as the cheapest
 * at a profit, sell it, and start over from a fresh page 1. Free of game classes.
 */
public final class FlipRoutine extends AuctionRoutine {

    public static final String OWNER = "flip";
    /** Marks a result of {@link #browse} that found nothing. */
    private static final Object NOTHING = new Object();

    /** Item key -> tick until which its market is not checked again. */
    private final Map<String, Long> checked = new HashMap<>();

    public FlipRoutine(AuctionGame game, AuctionHost host, Supplier<AuctionSetup> setup) {
        super(game, host, setup);
    }

    @Override
    protected void cycle(long now) {
        AuctionSetup s = setup.get();
        int held = 0;
        for (AuctionLedger.Entry e : host.ledger().entries()) {
            if (e.owner.equals(OWNER) && e.status == AuctionLedger.Status.BOUGHT) {
                if (due(e, now)) {
                    sell(e, (result, at) -> {
                    });
                    return;
                }
                held++;
            }
        }
        if (readMoney(now)) {
            return;
        }
        if (held >= s.maxHeld) {
            rest(now, s.restTicks, "holding " + held + " items until they can be sold at a profit");
            return;
        }
        String blocked = host.limit(s, 0);
        if (blocked != null) {
            rest(now, s.restTicks, blocked);
            return;
        }
        browse(new AuctionPager(game, s, "ah"));
    }

    private void browse(AuctionPager pager) {
        status = "browsing /ah";
        await(now -> {
            AuctionGame.Window w = pager.tick(now);
            if (pager.failed()) {
                return NOTHING;
            }
            if (w == null) {
                return null;
            }
            for (AuctionDeals.Deal d : host.candidates(w.slots(), now)) {
                if (now >= checked.getOrDefault(d.view().key(), 0L)
                        && host.limit(setup.get(), d.price()) == null) {
                    return d;
                }
            }
            return pager.page() >= setup.get().browsePages || !pager.next(now) ? NOTHING : null;
        }, (found, now) -> {
            if (found instanceof AuctionDeals.Deal d) {
                check(d, now);
            } else {
                // Starting again reopens /ah: a refresh, back on page 1.
                String why = pager.problem();
                rest(now, setup.get().restTicks, why != null ? why : "nothing to flip, refreshing soon");
                game.close();
            }
        });
    }

    private void check(AuctionDeals.Deal deal, long start) {
        AuctionSetup s = setup.get();
        AuctionDeals.SlotView v = deal.view();
        checked.put(v.key(), start + s.recheckTicks);
        status = "checking the market for " + v.name();
        await(new MarketScan(game, s, new AuctionGame.Item(v.itemId(), v.name(), v.key())), (market, now) -> {
            String no = "no listings found for " + v.name();
            // Cheapest per item first: buy only what can be relisted under everyone else at a profit.
            for (MarketScan.Offer offer : market.offers().stream()
                    .sorted(Comparator.comparingDouble(MarketScan.Offer::lowPerItem)).toList()) {
                MarketPrice.Quote quote = MarketPrice.quote(market.without(offer), offer.item().count(),
                        offer.price().high(), host.ledger().askedPerItem(v.key(), host.millis() - 30 * 60_000L), s);
                no = quote.price() < 0 ? quote.why() : host.limit(s, offer.price().high());
                if (no == null) {
                    buy(market, offer, OWNER, (result, at) -> {
                        AuctionLedger.Entry e = host.ledger().byId(result.entryId());
                        if (result.bought() && e != null) {
                            sell(e, (sold, later) -> {
                            });
                        }
                    });
                    return;
                }
                if (quote.price() < 0) {
                    break; // dearer listings can only be worse
                }
            }
            status = no;
            game.close();
        });
    }
}
