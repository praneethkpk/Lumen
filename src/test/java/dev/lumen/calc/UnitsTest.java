package dev.lumen.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UnitsTest {

    private static double convert(String query) {
        Units.Result result = Units.convert(query);
        assertNotNull(result, "expected a conversion for: " + query);
        return result.value();
    }

    @Test
    void convertsLength() {
        assertEquals(6.2137, convert("10 km to miles"), 0.001);
        assertEquals(2.54, convert("1 in to cm"), 0.0001);
        assertEquals(0.3048, convert("1 foot in metres"), 0.0001);
    }

    @Test
    void convertsMass() {
        assertEquals(2.20462, convert("1 kg to lb"), 0.001);
        assertEquals(1000, convert("1 kg in g"), 0.0001);
    }

    @Test
    void convertsTemperature() {
        assertEquals(212, convert("100 c to f"), 0.0001);
        assertEquals(0, convert("32 f to c"), 0.0001);
        assertEquals(273.15, convert("0 c to k"), 0.0001);
    }

    @Test
    void convertsDataAndTime() {
        assertEquals(1024, convert("1 mb to kb"), 0.0001);
        assertEquals(1.5, convert("90 min to hours"), 0.0001);
    }

    @Test
    void rejectsIncompatibleOrUnknownUnits() {
        assertNull(Units.convert("10 km to kg"));
        assertNull(Units.convert("10 flurbs to km"));
        assertNull(Units.convert("hello world"));
        assertNull(Units.convert("10 km"));
    }
}
