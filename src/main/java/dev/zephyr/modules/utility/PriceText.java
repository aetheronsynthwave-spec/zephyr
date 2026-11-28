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

    /**
     * A price as shown. {@code step} is the value of its last shown digit ("1.1K" -> 100)
     * and {@code unit} its suffix (1000 for K), so the real price is only known that closely.
     */
    public record Amount(double value, double step, double unit) {
        /** The lowest the real price can be, whether the server rounds or cuts off. */
        public double low() {
            return step > 1 ? value - step / 2 : value;
        }

        /** The highest the real price can be. */
        public double high() {
            return step > 1 ? value + step - 1 : value;
        }

        /** The same price when the server is known to keep {@code decimals} decimals (0: unknown). */
        public Amount precise(int decimals) {
            return decimals > 0 && unit > 1
                    ? new Amount(value, Math.min(step, unit / Math.pow(10, decimals)), unit) : this;
        }
    }

    /** The listing price in these lore lines, or -1. A line that names the price wins. */
    public static double price(List<String> lines) {
        Amount a = read(lines);
        return a == null ? -1 : a.value();
    }

    /** Like {@link #price}, with its precision; null when there is none. */
    public static Amount read(List<String> lines) {
        for (String line : lines) {
            Matcher m = LABELLED.matcher(line);
            if (m.find()) {
                return amount(m.group(1), m.group(2));
            }
        }
        for (String line : lines) {
            Matcher m = DOLLARS.matcher(line);
            // "Worth" and balance lines are not what the listing costs.
            if (m.find() && !line.toLowerCase(Locale.ROOT).matches(".*(worth|balance|value|tax).*")) {
                return amount(m.group(1), m.group(2));
            }
        }
        return null;
    }

    private static Amount amount(String number, String suffix) {
        String plain = number.replace(",", "");
        double unit = suffix == null ? 1 : switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1.0e3;
            case "m" -> 1.0e6;
            case "b" -> 1.0e9;
            default -> 1.0e12;
        };
        int dot = plain.indexOf('.');
        int decimals = dot < 0 ? 0 : plain.length() - dot - 1;
        return new Amount(Double.parseDouble(plain) * unit, unit / Math.pow(10, decimals), unit);
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
