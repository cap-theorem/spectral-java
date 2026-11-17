package io.github.captheorem.spectral;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

final class GoDuration {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final String[] UNITS = {"ns", "us", "µs", "μs", "ms", "s", "m", "h"};
    private static final long[] UNIT_NANOS = {
        1L, 1_000L, 1_000L, 1_000L, 1_000_000L, NANOS_PER_SECOND, 60 * NANOS_PER_SECOND, 3600 * NANOS_PER_SECOND,
    };

    private GoDuration() {
    }

    static String format(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos % NANOS_PER_SECOND == 0) {
            return (nanos / NANOS_PER_SECOND) + "s";
        }
        if (nanos % NANOS_PER_MILLI == 0) {
            return (nanos / NANOS_PER_MILLI) + "ms";
        }
        return nanos + "ns";
    }

    static Duration parse(String text) {
        String rest = text.strip();
        if (rest.startsWith("+")) {
            rest = rest.substring(1);
        }
        if (rest.equals("0")) {
            return Duration.ZERO;
        }
        if (rest.isEmpty() || rest.startsWith("-")) {
            throw new IllegalArgumentException("invalid duration \"" + text + "\"");
        }

        BigDecimal total = BigDecimal.ZERO;
        int pos = 0;
        while (pos < rest.length()) {
            int start = pos;
            while (pos < rest.length() && (Character.isDigit(rest.charAt(pos)) || rest.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("invalid duration \"" + text + "\"");
            }

            BigDecimal amount;
            try {
                amount = new BigDecimal(rest.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid duration \"" + text + "\"", e);
            }

            int unit = matchUnit(rest, pos);
            if (unit < 0) {
                throw new IllegalArgumentException("invalid duration \"" + text + "\"");
            }
            total = total.add(amount.multiply(BigDecimal.valueOf(UNIT_NANOS[unit])));
            pos += UNITS[unit].length();
        }

        return Duration.ofNanos(total.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    private static int matchUnit(String text, int pos) {
        for (int i = 0; i < UNITS.length; i++) {
            if (text.startsWith(UNITS[i], pos)) {
                return i;
            }
        }
        return -1;
    }
}
