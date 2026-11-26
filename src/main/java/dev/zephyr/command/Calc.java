package dev.zephyr.command;

/**
 * Tiny recursive-descent calculator for {@code /zephyr calc}: + - * / % ^, parentheses,
 * unary minus, and sqrt/abs/floor/ceil/round/sin/cos/tan/log/ln, plus pi and e.
 * Handy for stack maths ("64*27"), nether coords ("-1840/8") and so on.
 */
final class Calc {

    private final String s;
    private int pos;

    private Calc(String s) {
        this.s = s.replace(" ", "").toLowerCase();
    }

    static double eval(String expr) {
        Calc c = new Calc(expr);
        double v = c.expr();
        if (c.pos != c.s.length()) {
            throw new IllegalArgumentException("unexpected '" + c.s.charAt(c.pos) + "'");
        }
        return v;
    }

    private double expr() {
        double v = term();
        while (pos < s.length()) {
            char ch = s.charAt(pos);
            if (ch == '+') {
                pos++;
                v += term();
            } else if (ch == '-') {
                pos++;
                v -= term();
            } else {
                break;
            }
        }
        return v;
    }

    private double term() {
        double v = power();
        while (pos < s.length()) {
            char ch = s.charAt(pos);
            if (ch == '*') {
                pos++;
                v *= power();
            } else if (ch == '/') {
                pos++;
                v /= power();
            } else if (ch == '%') {
                pos++;
                v %= power();
            } else {
                break;
            }
        }
        return v;
    }

    private double power() {
        double base = unary();
        if (pos < s.length() && s.charAt(pos) == '^') {
            pos++;
            return Math.pow(base, power()); // right-associative
        }
        return base;
    }

    private double unary() {
        if (pos < s.length() && s.charAt(pos) == '-') {
            pos++;
            return -unary();
        }
        if (pos < s.length() && s.charAt(pos) == '+') {
            pos++;
            return unary();
        }
        return atom();
    }

    private double atom() {
        if (pos >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        char ch = s.charAt(pos);
        if (ch == '(') {
            pos++;
            double v = expr();
            expect(')');
            return v;
        }
        if (Character.isDigit(ch) || ch == '.') {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            return Double.parseDouble(s.substring(start, pos));
        }
        if (Character.isLetter(ch)) {
            int start = pos;
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                pos++;
            }
            String name = s.substring(start, pos);
            switch (name) {
                case "pi": return Math.PI;
                case "e": return Math.E;
                default: break;
            }
            expect('(');
            double a = expr();
            expect(')');
            return switch (name) {
                case "sqrt" -> Math.sqrt(a);
                case "abs" -> Math.abs(a);
                case "floor" -> Math.floor(a);
                case "ceil" -> Math.ceil(a);
                case "round" -> Math.round(a);
                case "sin" -> Math.sin(Math.toRadians(a));
                case "cos" -> Math.cos(Math.toRadians(a));
                case "tan" -> Math.tan(Math.toRadians(a));
                case "log" -> Math.log10(a);
                case "ln" -> Math.log(a);
                default -> throw new IllegalArgumentException("unknown function " + name);
            };
        }
        throw new IllegalArgumentException("unexpected '" + ch + "'");
    }

    private void expect(char c) {
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw new IllegalArgumentException("expected '" + c + "'");
        }
        pos++;
    }
}
