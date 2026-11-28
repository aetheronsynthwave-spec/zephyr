package dev.zephyr.modules.utility;

import java.util.ArrayList;
import java.util.List;

/**
 * The price to list at: just under the cheapest competitor, and never under cost plus the
 * required profit. Free of game classes.
 */
public final class MarketPrice {

    /** {@code price} is -1 when it must not be listed; {@code why} then says why. */
    public record Quote(long price, String why) {
    }

    private MarketPrice() {
    }

    /** The lowest price that still returns cost plus profit after the fee. */
    public static long floor(double paidHigh, AuctionSetup s) {
        double wanted = Math.max(paidHigh * (1.0 + s.minGain), paidHigh + 1);
        return (long) Math.ceil(wanted / (1.0 - s.fee) - 1e-9);
    }

    /**
     * @param count     size of the stack to list
     * @param paidHigh  the most that stack can have cost
     * @param ownAsks   what you currently ask per item, so you do not undercut yourself
     */
    public static Quote quote(MarketScan.Market market, int count, double paidHigh, List<Double> ownAsks,
                              AuctionSetup s) {
        String name = market.item().name();
        if (!market.complete()) {
            return new Quote(-1, "could not read the whole market for " + name + ": " + market.problem());
        }
        if (market.offers().isEmpty()) {
            return new Quote(-1, "nobody else lists " + name + ", so there is no lowest price to go by");
        }
        List<Double> unmatched = new ArrayList<>(ownAsks);
        List<Double> mine = new ArrayList<>();
        double others = Double.POSITIVE_INFINITY;
        for (MarketScan.Offer o : market.offers()) {
            double low = o.price().low() / o.item().count();
            double high = o.price().high() / o.item().count();
            // A listing that reads like one of your asks is taken to be yours, once per ask.
            Double own = unmatched.stream().filter(a -> a >= low - 1e-9 && a <= high + 1e-9).findFirst()
                    .orElse(null);
            if (own != null) {
                unmatched.remove(own);
                mine.add(own);
            } else {
                others = Math.min(others, low);
            }
        }
        double price = others * count - s.undercut;
        for (double a : mine) {
            price = Math.min(price, a * count);
        }
        long listed = (long) Math.floor(price + 1e-9);
        long floor = floor(paidHigh, s);
        if (listed < floor) {
            return new Quote(-1, name + ": cheapest would be $" + PriceText.shorten(Math.max(0, listed))
                    + " but profit needs $" + PriceText.shorten(floor) + ", holding");
        }
        return new Quote(listed, null);
    }

    /** After listing {@code count} for {@code asked}: what a fresh search says about your place. */
    public static String standing(MarketScan.Market market, int count, long asked) {
        if (!market.complete()) {
            return "could not re-check the market: " + market.problem();
        }
        double per = (double) asked / count;
        boolean seen = false;
        double under = Double.POSITIVE_INFINITY;
        for (MarketScan.Offer o : market.offers()) {
            double low = o.price().low() / o.item().count();
            double high = o.price().high() / o.item().count();
            if (!seen && per >= low - 1e-9 && per <= high + 1e-9) {
                seen = true;
            } else if (high < per - 1e-9) {
                under = Math.min(under, o.price().value() / o.item().count());
            }
        }
        if (under < Double.POSITIVE_INFINITY) {
            return "someone is already cheaper, at about $" + PriceText.shorten(under) + " each";
        }
        return seen ? "re-checked: yours is the cheapest" : "not visible in the search yet";
    }
}
