package dev.lumen.providers;

import dev.lumen.calc.Expression;
import dev.lumen.calc.Units;
import dev.lumen.core.Hit;
import dev.lumen.core.IndexItem;
import dev.lumen.core.ItemKind;
import dev.lumen.util.Numbers;

import java.util.List;
import java.util.Locale;

/**
 * Inline calculator and unit converter.
 *
 * <p>Always ranks above file results when it matches: if you typed an expression you
 * meant the expression.
 */
public final class CalculatorProvider implements Provider {

    private static final int SCORE = 100_000;

    @Override
    public String id() {
        return "calculator";
    }

    @Override
    public List<String> aliases() {
        return List.of("calc", "=");
    }

    @Override
    public int minQueryLength() {
        return 2;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public List<Hit> search(String query) {
        String q = query.trim();
        if (q.length() < 2 || q.length() > 120) {
            return List.of();
        }

        Units.Result conversion = Units.convert(q);
        if (conversion != null) {
            return single(q, conversion.value(), conversion.unit());
        }

        if (!looksLikeMath(q)) {
            return List.of();
        }

        Double value = Expression.evaluate(q);
        if (value == null) {
            return List.of();
        }
        return single(q, value, null);
    }

    private static List<Hit> single(String query, double value, String unit) {
        String formatted = Numbers.format(value);
        if (formatted == null) {
            return List.of();
        }
        String title = unit == null ? formatted : formatted + " " + unit;
        String subtitle = query + "   ·   Enter copies";
        return List.of(new Hit(new IndexItem(title, subtitle, title, ItemKind.CALC), SCORE));
    }

    private static boolean looksLikeMath(String q) {
        if (!q.chars().anyMatch(Character::isDigit)) {
            return false;
        }
        String trimmed = q.trim();
        // Keep date-like and phone-like inputs out of the calculator: 2025-03-04 or
        // 1-800-... should be searched for, not evaluated as arithmetic.
        if (trimmed.matches("\\d{4}[-/]\\d{1,2}([-/]\\d{1,2})?")
                || trimmed.matches("\\d{1,4}[-/]\\d{3,4}")) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.contains("+") || lower.contains("-") || lower.contains("*") || lower.contains("/")
                || lower.contains("%") || lower.contains("^") || lower.contains("(");
    }
}
