package dev.zephyr.modules.utility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading prices out of auction lore, and what counts as a bargain. Free of game classes. */
public final class PriceText {

    /** "$1,250", "$ 4.5M", "Price: 300k"; a bare number only counts after the word price or buy. */
    private static final Pattern DOLLARS = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?(?![a-z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LABELLED = Pattern.compile(
            "(?:price|buy now|cost)\\s*:?\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?(?![a-z0-9])",
            Pattern.CASE_INSENSITIVE);

    private PriceText() {
    }

    /** The listing price in these lore lines, or -1. A line that names the price wins. */
    public static double price(List<String> lines) {
        for (String line : lines) {
            Matcher m = LABELLED.matcher(line);
            if (m.find()) {
                return value(m.group(1), m.group(2));
            }
        }
        for (String line : lines) {
            Matcher m = DOLLARS.matcher(line);
            // "Worth" and balance lines are not what the listing costs.
            if (m.find() && !line.toLowerCase(Locale.ROOT).matches(".*(worth|balance|value|tax).*")) {
                return value(m.group(1), m.group(2));
            }
        }
        return -1;
    }

    private static double value(String number, String suffix) {
        double amount = Double.parseDouble(number.replace(",", ""));
        if (suffix == null) {
            return amount;
        }
        return amount * switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1.0e3;
            case "m" -> 1.0e6;
            case "b" -> 1.0e9;
            default -> 1.0e12;
        };
    }

    /** Median of the prices; -1 when there are none. */
    public static double median(List<Double> prices) {
        if (prices.isEmpty()) {
            return -1;
        }
        List<Double> sorted = new ArrayList<>(prices);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /** Short form for a label: 1250 -> "1.25k". */
    public static String shorten(double amount) {
        double size = Math.abs(amount);
        String[] suffix = {"", "k", "m", "b", "t"};
        int step = 0;
        while (size >= 1000 && step < suffix.length - 1) {
            size /= 1000;
            step++;
        }
        String number = String.format(Locale.ROOT, size >= 100 || step == 0 ? "%.0f" : size >= 10 ? "%.1f" : "%.2f", size);
        return (amount < 0 ? "-" : "") + number + suffix[step];
    }
}
