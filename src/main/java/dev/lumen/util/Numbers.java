package dev.lumen.util;

import java.math.BigDecimal;
import java.math.MathContext;

/** Number formatting shared by the calculator and unit converter. */
public final class Numbers {

    private Numbers() {
    }

    /**
     * Human-friendly rendering: "12" not "12.0", "0.5" not "5E-1", and 10 significant
     * digits for repeating decimals.
     */
    public static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        BigDecimal bd = BigDecimal.valueOf(value).round(new MathContext(10)).stripTrailingZeros();
        String plain = bd.toPlainString();
        return plain.length() > 24 ? bd.toString() : plain;
    }
}
