package dev.zephyr.modules.utility;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the server's "bought" and "sold" chat lines from a template such as
 * {@code You bought {count} {item} for $ {price}}. A template must name {item} and
 * {price}; {count}, {player} and {any} are optional. The whole line has to match, so a
 * player quoting the message in chat is not read. Free of game classes.
 */
public final class AuctionChat {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(item|price|count|player|any)}");

    /** {@code count} is -1 when the template has no {count}. */
    public record Hit(String item, int count, double price) {
    }

    private AuctionChat() {
    }

    /** @return the compiled template, or null when it is blank or cannot be used. */
    public static Pattern compile(String template) {
        if (template == null || !template.contains("{item}") || !template.contains("{price}")) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(template.trim());
        String t = template.trim();
        int at = 0;
        java.util.Set<String> used = new java.util.HashSet<>();
        while (m.find()) {
            literal(regex, t.substring(at, m.start()));
            String name = m.group(1);
            if (!name.equals("any") && !used.add(name)) {
                return null;
            }
            regex.append(switch (name) {
                case "item" -> "(?<item>.+?)";
                case "price" -> "(?<price>[0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kKmMbBtT]?)";
                case "count" -> "(?<count>[0-9][0-9,]{0,8})x?";
                case "player" -> "\\S+";
                default -> ".*?";
            });
            at = m.end();
        }
        literal(regex, t.substring(at));
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static void literal(StringBuilder regex, String text) {
        String[] parts = text.split("\\s+", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append("\\s*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
    }

    public static Hit match(Pattern pattern, String line) {
        if (pattern == null || line == null) {
            return null;
        }
        Matcher m = pattern.matcher(line.replaceAll("§.", "").trim());
        if (!m.matches()) {
            return null;
        }
        double price = amount(m.group("price"));
        int count = -1;
        if (pattern.pattern().contains("(?<count>")) {
            long c = Long.parseLong(m.group("count").replace(",", ""));
            if (c <= 0 || c > 1_000_000) {
                return null;
            }
            count = (int) c;
        }
        return price > 0 ? new Hit(m.group("item").trim(), count, price) : null;
    }

    /** "23", "1.1K" or "2,500" as a number; -1 when it is not one. */
    public static double amount(String text) {
        String t = text.replace(" ", "");
        if (!t.matches("(?i)[0-9][0-9,]*(\\.[0-9]+)?[kmbt]?")) {
            return -1;
        }
        return PriceText.price(List.of("$" + t));
    }
}
