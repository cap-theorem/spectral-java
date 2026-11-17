package io.github.captheorem.spectral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WireTest {
    @Test
    void parsesGoDurations() {
        Map<String, Duration> cases = Map.of(
                "10s", Duration.ofSeconds(10),
                "1m30s", Duration.ofSeconds(90),
                "1.5s", Duration.ofMillis(1500),
                "250ms", Duration.ofMillis(250),
                "1h", Duration.ofHours(1),
                "0s", Duration.ZERO,
                "0", Duration.ZERO,
                "500µs", Duration.ofNanos(500_000));
        cases.forEach((text, want) -> assertEquals(want, GoDuration.parse(text), text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "10", "10x", "s", "1s2", "-2s"})
    void rejectsBadGoDurations(String text) {
        assertThrows(IllegalArgumentException.class, () -> GoDuration.parse(text));
    }

    @Test
    void formatsGoDurations() {
        assertEquals("10s", GoDuration.format(Duration.ofSeconds(10)));
        assertEquals("1500ms", GoDuration.format(Duration.ofMillis(1500)));
        assertEquals("1500ns", GoDuration.format(Duration.ofNanos(1500)));
    }

    @Test
    void jsonRoundTrip() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("plain", "value");
        fields.put("tricky", "quote \" backslash \\ newline \n tab \t control \u0001 unicode é");

        String encoded = Json.object(fields);
        assertEquals(Map.copyOf(fields), Json.parseObject(encoded));
    }

    @Test
    void jsonParsesNestedValues() {
        Map<String, Object> parsed = Json.parseObject(
                " {\"a\": true, \"b\": null, \"c\": 1.5, \"d\": [1, \"x\"], \"e\": {\"f\": \"\\u00e9\"}} ");
        assertEquals(Boolean.TRUE, parsed.get("a"));
        assertEquals(null, parsed.get("b"));
        assertEquals(1.5, parsed.get("c"));
        assertEquals(List.of(1.0, "x"), parsed.get("d"));
        assertEquals(Map.of("f", "é"), parsed.get("e"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "{", "{\"a\":}", "{\"a\":1} x", "{'a':1}"})
    void jsonRejectsMalformedInput(String text) {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(text));
    }
}
