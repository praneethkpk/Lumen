package dev.lumen.calc;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unit conversion for the handful of units people actually type into a launcher. */
public final class Units {

    /** The converted value plus how to render it. */
    public record Result(double value, String unit, String source) {
    }

    private static final Pattern PATTERN = Pattern.compile(
            "^\\s*(-?\\d+(?:[.,]\\d+)?)\\s*([a-zA-Zµ°]+)\\s*(?:into|to|in|->|>)\\s*([a-zA-Zµ°]+)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Double> LENGTH = Map.ofEntries(
            Map.entry("mm", 0.001), Map.entry("cm", 0.01), Map.entry("m", 1.0),
            Map.entry("km", 1_000.0), Map.entry("in", 0.0254), Map.entry("ft", 0.3048),
            Map.entry("yd", 0.9144), Map.entry("mi", 1_609.344), Map.entry("nm", 1e-9)
    );

    private static final Map<String, Double> MASS = Map.ofEntries(
            Map.entry("mg", 0.001), Map.entry("g", 1.0), Map.entry("kg", 1_000.0),
            Map.entry("oz", 28.349523125), Map.entry("lb", 453.59237), Map.entry("t", 1_000_000.0)
    );

    private static final Map<String, Double> DATA = Map.ofEntries(
            Map.entry("b", 1.0), Map.entry("kb", 1024.0), Map.entry("mb", 1_048_576.0),
            Map.entry("gb", 1_073_741_824.0), Map.entry("tb", 1_099_511_627_776.0)
    );

    private static final Map<String, Double> TIME = Map.ofEntries(
            Map.entry("ms", 0.001), Map.entry("s", 1.0), Map.entry("min", 60.0),
            Map.entry("h", 3_600.0), Map.entry("day", 86_400.0), Map.entry("week", 604_800.0)
    );

    private static final Map<String, Double> VOLUME = Map.ofEntries(
            Map.entry("ml", 0.001), Map.entry("l", 1.0), Map.entry("cup", 0.2365882365),
            Map.entry("pt", 0.473176473), Map.entry("qt", 0.946352946),
            Map.entry("gal", 3.785411784), Map.entry("floz", 0.0295735295625)
    );

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        alias("kilometers", "km");
        alias("kilometres", "km");
        alias("kilometer", "km");
        alias("metres", "m");
        alias("meters", "m");
        alias("meter", "m");
        alias("centimeters", "cm");
        alias("centimetres", "cm");
        alias("millimeters", "mm");
        alias("millimetres", "mm");
        alias("miles", "mi");
        alias("mile", "mi");
        alias("feet", "ft");
        alias("foot", "ft");
        alias("inches", "in");
        alias("inch", "in");
        alias("yards", "yd");
        alias("kilograms", "kg");
        alias("kilogram", "kg");
        alias("kgs", "kg");
        alias("grams", "g");
        alias("gram", "g");
        alias("pounds", "lb");
        alias("pound", "lb");
        alias("lbs", "lb");
        alias("ounces", "oz");
        alias("ounce", "oz");
        alias("tonnes", "t");
        alias("tonne", "t");
        alias("bytes", "b");
        alias("kilobytes", "kb");
        alias("megabytes", "mb");
        alias("gigabytes", "gb");
        alias("terabytes", "tb");
        alias("seconds", "s");
        alias("secs", "s");
        alias("sec", "s");
        alias("minutes", "min");
        alias("mins", "min");
        alias("hours", "h");
        alias("hrs", "h");
        alias("hr", "h");
        alias("days", "day");
        alias("weeks", "week");
        alias("liters", "l");
        alias("litres", "l");
        alias("liter", "l");
        alias("litre", "l");
        alias("milliliters", "ml");
        alias("gallons", "gal");
        alias("gallon", "gal");
        alias("quarts", "qt");
        alias("pints", "pt");
        alias("ounces_fl", "floz");
        alias("celsius", "c");
        alias("centigrade", "c");
        alias("fahrenheit", "f");
        alias("kelvin", "k");
    }

    private Units() {
    }

    private static void alias(String from, String to) {
        ALIASES.put(from, to);
    }

    /** Returns {@code null} when the text is not a unit conversion. */
    public static Result convert(String query) {
        Matcher matcher = PATTERN.matcher(query);
        if (!matcher.matches()) {
            return null;
        }

        double value;
        try {
            value = Double.parseDouble(matcher.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }

        String from = normalize(matcher.group(2));
        String to = normalize(matcher.group(3));
        if (from == null || to == null) {
            return null;
        }

        Double converted = convertTemperature(value, from, to);
        if (converted == null) {
            converted = convertFactor(value, from, to);
        }
        if (converted == null) {
            return null;
        }
        return new Result(converted, to, from);
    }

    private static Double convertTemperature(double value, String from, String to) {
        if (!isTemperature(from) || !isTemperature(to)) {
            return null;
        }
        double celsius = switch (from) {
            case "c" -> value;
            case "f" -> (value - 32) * 5 / 9;
            case "k" -> value - 273.15;
            default -> throw new IllegalStateException();
        };
        return switch (to) {
            case "c" -> celsius;
            case "f" -> celsius * 9 / 5 + 32;
            case "k" -> celsius + 273.15;
            default -> throw new IllegalStateException();
        };
    }

    private static boolean isTemperature(String unit) {
        return unit.equals("c") || unit.equals("f") || unit.equals("k");
    }

    private static Double convertFactor(double value, String from, String to) {
        for (Map<String, Double> table : new Map[]{LENGTH, MASS, DATA, TIME, VOLUME}) {
            Double a = table.get(from);
            Double b = table.get(to);
            if (a != null && b != null) {
                return value * a / b;
            }
        }
        return null;
    }

    private static String normalize(String raw) {
        String unit = raw.toLowerCase(Locale.ROOT).replace("°", "").trim();
        unit = ALIASES.getOrDefault(unit, unit);
        if (unit.equals("µs")) {
            return "us";
        }
        return unit;
    }
}
