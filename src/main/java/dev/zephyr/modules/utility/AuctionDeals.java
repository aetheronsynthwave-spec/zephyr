package dev.zephyr.modules.utility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Which listings are deals, and which deal is best. Free of game classes. */
public final class AuctionDeals {

    public enum Reference { MEDIAN, LOWER_QUARTILE, CHEAPEST_OTHER }

    public enum Rank { PROFIT, ROI, DISCOUNT, CHEAPEST }

    /** One container slot as read from the window. */
    public record SlotView(int slot, String itemId, String key, String name, int count, List<String> lore,
                           boolean container) {
    }

    /** A listing that passed the deal filters. Money is in server currency units. */
    public record Deal(SlotView view, String identity, double price, double profit, double roi, double discount,
                       double reference) {
    }

    /** A priced listing and the identity it has on this page. */
    private record Listed(SlotView view, String identity, double price) {
    }

    /** Hand-written rules from the JSON file. Entries are item ids or parts of item names. */
    public static final class Rules {
        public List<String> whitelist = new ArrayList<>();
        public List<String> blacklist = new ArrayList<>();
        /** Item id or name part -> highest price to pay per item. */
        public Map<String, Double> maxUnitPrice = new LinkedHashMap<>();
        /** Item id or name part -> your own resale price per item (replaces the browsed reference). */
        public Map<String, Double> resaleUnitPrice = new LinkedHashMap<>();
    }

    public static final class Filter {
        public Reference reference = Reference.MEDIAN;
        public Rank rank = Rank.PROFIT;
        public double minDiscount = 0.30;
        public double minProfit = 10_000;
        public double minRoi = 0.0;
        public int samples = 5;
        public double fee = 0.0;
        public double minPrice = 0.0;
        public long historyTicks = 20L * 60 * 30;
        public boolean skipContainers = true;
        public boolean whitelistOnly = false;
        /** Slots below this index are listings, the rest are buttons. Zero reads every slot. */
        public int listingSlots = 0;
    }

    private static final int HISTORY = 120;
    /** Lore lines that count down; they must not make one listing look like many. */
    private static final Pattern TIMER = Pattern.compile(
            "(?i).*(\\b(time|expires?|expiring|ends|remaining|left)\\b|\\b\\d+d \\d+h\\b|\\b\\d+h \\d+m\\b|\\b\\d+m \\d+s\\b).*");

    private record Sample(double unit, long seen) {
    }

    private final Map<String, LinkedHashMap<String, Sample>> history = new HashMap<>();

    public void clear() {
        history.clear();
    }

    public static String identity(SlotView v, double price) {
        StringBuilder out = new StringBuilder(v.key()).append('|').append(v.count()).append('|').append(price);
        for (String line : v.lore()) {
            if (!TIMER.matcher(line).matches()) {
                out.append('|').append(line);
            }
        }
        return out.toString();
    }

    /** Records every priced listing, then returns the deals among them, best first. */
    public List<Deal> evaluate(List<SlotView> slots, Filter f, Rules rules, long now) {
        List<Listed> page = new ArrayList<>();
        // Listings that read the same (no seller or time line) are told apart by how many
        // of them are on the page, so each counts, and a redraw does not add more.
        Map<String, Integer> twins = new HashMap<>();
        for (SlotView v : slots) {
            double price = PriceText.price(v.lore());
            if (!usable(price) || v.count() <= 0 || f.listingSlots > 0 && v.slot() >= f.listingSlots) {
                continue;
            }
            String base = identity(v, price);
            String id = base + "#" + twins.merge(base, 1, Integer::sum);
            page.add(new Listed(v, id, price));
            LinkedHashMap<String, Sample> samples = history.computeIfAbsent(v.key(), k -> new LinkedHashMap<>());
            samples.remove(id); // re-inserting keeps the newest last
            samples.put(id, new Sample(price / v.count(), now));
            if (samples.size() > HISTORY) {
                samples.remove(samples.keySet().iterator().next());
            }
        }
        List<Deal> deals = new ArrayList<>();
        for (Listed listed : page) {
            Deal d = judge(listed, f, rules, now);
            if (d != null) {
                deals.add(d);
            }
        }
        deals.sort(order(f.rank));
        return deals;
    }

    private Deal judge(Listed listed, Filter f, Rules rules, long now) {
        SlotView v = listed.view();
        double price = listed.price();
        if (price < f.minPrice
                || f.skipContainers && v.container()
                || matches(rules.blacklist, v)
                || f.whitelistOnly && !matches(rules.whitelist, v)) {
            return null;
        }
        double unit = price / v.count();
        Double cap = lookup(rules.maxUnitPrice, v);
        if (cap != null && unit > cap) {
            return null;
        }
        String id = listed.identity();
        Double fixed = lookup(rules.resaleUnitPrice, v);
        double reference;
        if (fixed != null) {
            reference = fixed;
        } else {
            List<Double> others = new ArrayList<>();
            LinkedHashMap<String, Sample> samples = history.get(v.key());
            samples.values().removeIf(s -> now - s.seen() > f.historyTicks);
            for (Map.Entry<String, Sample> e : samples.entrySet()) {
                if (!e.getKey().equals(id)) {
                    others.add(e.getValue().unit()); // a listing never vouches for itself
                }
            }
            if (others.size() < Math.max(1, f.samples)) {
                return null;
            }
            reference = reference(others, f.reference);
        }
        if (!usable(reference)) {
            return null;
        }
        double profit = reference * v.count() * (1.0 - f.fee) - price;
        double roi = profit / price;
        double discount = 1.0 - unit / reference;
        if (discount < f.minDiscount || profit <= 0 || profit < f.minProfit || roi < f.minRoi) {
            return null;
        }
        return new Deal(v, id, price, profit, roi, discount, reference);
    }

    static double reference(List<Double> prices, Reference mode) {
        List<Double> sorted = new ArrayList<>(prices);
        Collections.sort(sorted);
        return switch (mode) {
            case MEDIAN -> PriceText.median(sorted);
            case LOWER_QUARTILE -> sorted.get((sorted.size() - 1) / 4);
            case CHEAPEST_OTHER -> sorted.get(0);
        };
    }

    private static Comparator<Deal> order(Rank rank) {
        Comparator<Deal> first = switch (rank) {
            case PROFIT -> Comparator.comparingDouble((Deal d) -> -d.profit());
            case ROI -> Comparator.comparingDouble((Deal d) -> -d.roi());
            case DISCOUNT -> Comparator.comparingDouble((Deal d) -> -d.discount());
            case CHEAPEST -> Comparator.comparingDouble((Deal d) -> d.price() / d.view().count());
        };
        return first.thenComparingDouble(Deal::price).thenComparingInt(d -> d.view().slot());
    }

    private static boolean usable(double money) {
        return Double.isFinite(money) && money > 0;
    }

    private static boolean matches(String entry, SlotView v) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        String e = entry.trim().toLowerCase(Locale.ROOT);
        // An id ("minecraft:diamond") matches exactly; anything else is part of the name.
        return e.contains(":") ? v.itemId().equals(e) : v.name().toLowerCase(Locale.ROOT).contains(e);
    }

    private static boolean matches(List<String> entries, SlotView v) {
        return entries != null && entries.stream().anyMatch(e -> matches(e, v));
    }

    private static Double lookup(Map<String, Double> map, SlotView v) {
        if (map != null) {
            for (Map.Entry<String, Double> e : map.entrySet()) {
                if (matches(e.getKey(), v) && e.getValue() != null) {
                    return e.getValue();
                }
            }
        }
        return null;
    }
}
