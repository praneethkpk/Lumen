package dev.lumen.calc;

import java.util.Locale;

/**
 * A small, dependency-free arithmetic evaluator.
 *
 * <p>Deliberately not a scripting engine: parsing "12 * (3 + 4)" must cost microseconds
 * because it happens on every keystroke.
 */
public final class Expression {

    private Expression() {
    }

    /** Returns {@code null} when the text is not a complete, finite expression. */
    public static Double evaluate(String source) {
        if (source == null || source.isBlank() || source.length() > 120) {
            return null;
        }
        try {
            Parser parser = new Parser(source);
            double value = parser.expression();
            parser.skipWhitespace();
            if (parser.pos != source.length() || !Double.isFinite(value)) {
                return null;
            }
            return value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Parser {

        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        double expression() {
            double value = term();
            while (true) {
                skipWhitespace();
                if (eat('+')) {
                    value += term();
                } else if (eat('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        private double term() {
            double value = unary();
            while (true) {
                skipWhitespace();
                if (eat('*')) {
                    value *= unary();
                } else if (eat('/')) {
                    double divisor = unary();
                    if (divisor == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value /= divisor;
                } else if (eat('%')) {
                    double divisor = unary();
                    if (divisor == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        private double unary() {
            skipWhitespace();
            if (eat('-')) {
                return -unary();
            }
            if (eat('+')) {
                return unary();
            }
            return power();
        }

        private double power() {
            double base = primary();
            skipWhitespace();
            if (eat('^')) {
                return Math.pow(base, unary());
            }
            return base;
        }

        private double primary() {
            skipWhitespace();
            if (eat('(')) {
                double value = expression();
                skipWhitespace();
                if (!eat(')')) {
                    throw new ArithmeticException("missing )");
                }
                return value;
            }
            if (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                return number();
            }
            return identifier();
        }

        private double number() {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                int mark = pos;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                        pos++;
                    }
                } else {
                    pos = mark;
                }
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        private double identifier() {
            int start = pos;
            while (pos < src.length() && Character.isLetter(src.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw new ArithmeticException("unexpected char");
            }
            String name = src.substring(start, pos).toLowerCase(Locale.ROOT);

            double constant = switch (name) {
                case "pi" -> Math.PI;
                case "e" -> Math.E;
                case "tau" -> Math.PI * 2;
                default -> Double.NaN;
            };
            if (!Double.isNaN(constant)) {
                return constant;
            }

            skipWhitespace();
            if (!eat('(')) {
                throw new ArithmeticException("expected ( after " + name);
            }
            double arg = expression();
            skipWhitespace();
            if (!eat(')')) {
                throw new ArithmeticException("missing )");
            }
            return switch (name) {
                case "sqrt" -> Math.sqrt(arg);
                case "abs" -> Math.abs(arg);
                case "round" -> Math.round(arg);
                case "floor" -> Math.floor(arg);
                case "ceil" -> Math.ceil(arg);
                case "sin" -> Math.sin(arg);
                case "cos" -> Math.cos(arg);
                case "tan" -> Math.tan(arg);
                case "asin" -> Math.asin(arg);
                case "acos" -> Math.acos(arg);
                case "atan" -> Math.atan(arg);
                case "ln" -> Math.log(arg);
                case "log" -> Math.log10(arg);
                case "exp" -> Math.exp(arg);
                case "sign" -> Math.signum(arg);
                default -> throw new ArithmeticException("unknown function " + name);
            };
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private boolean eat(char c) {
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }
    }
}
