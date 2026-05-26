package io.github.ulviar.icli.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

final class JsonNumbersTest {

    @Test
    void acceptsValidJsonNumberTokens() {
        for (String token : java.util.List.of("0", "-0", "1", "-12", "12.5", "1e2", "1E-2", "1.2e+3")) {
            assertTrue(JsonNumbers.isJsonNumber(token));
            assertEquals(new BigDecimal(token), JsonNumbers.parse(token));
        }
    }

    @Test
    void rejectsInvalidJsonNumberTokens() {
        assertFalse(JsonNumbers.isJsonNumber(null));
        assertThrows(NullPointerException.class, () -> JsonNumbers.parse(null));

        for (String token :
                java.util.List.of("", "-", "01", "+1", ".1", "1.", "1e", "1e+", "1e-", "1..2", "NaN", "Infinity")) {
            assertFalse(JsonNumbers.isJsonNumber(token));
            assertThrows(JsonParseException.class, () -> JsonNumbers.parse(token));
        }
    }
}
