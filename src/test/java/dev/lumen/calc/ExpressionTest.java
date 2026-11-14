package dev.lumen.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExpressionTest {

    @Test
    void respectsPrecedence() {
        assertEquals(14.0, Expression.evaluate("2 + 3 * 4"));
        assertEquals(20.0, Expression.evaluate("(2 + 3) * 4"));
        assertEquals(2.0, Expression.evaluate("10 / 5"));
    }

    @Test
    void handlesPowerRightAssociatively() {
        assertEquals(512.0, Expression.evaluate("2^3^2"));
        assertEquals(8.0, Expression.evaluate("2^3"));
    }

    @Test
    void handlesUnaryMinus() {
        assertEquals(-5.0, Expression.evaluate("-5"));
        assertEquals(-3.0, Expression.evaluate("2 + -5"));
        assertEquals(6.0, Expression.evaluate("2 * -3 * -1"));
    }

    @Test
    void knowsFunctionsAndConstants() {
        assertEquals(3.0, Expression.evaluate("sqrt(9)"));
        assertEquals(Math.PI, Expression.evaluate("pi"));
        assertEquals(4.0, Expression.evaluate("log(10000)"));
    }

    @Test
    void supportsScientificNotation() {
        assertEquals(1500.0, Expression.evaluate("1.5e3"));
    }

    @Test
    void rejectsGarbage() {
        assertNull(Expression.evaluate("12 +"));
        assertNull(Expression.evaluate("hello"));
        assertNull(Expression.evaluate("2 + 3 world"));
        assertNull(Expression.evaluate("1/0"));
        assertNull(Expression.evaluate(""));
    }
}
