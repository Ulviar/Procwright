package com.github.ulviar.icli;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class DurationSupport {

    private DurationSupport() {}

    static long saturatedNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    static long deadlineFromNow(Duration duration) {
        return deadlineFrom(System.nanoTime(), duration);
    }

    static long deadlineFrom(long currentNanos, Duration duration) {
        return currentNanos + saturatedNanos(duration);
    }

    static long remainingMillis(long deadlineNanos) {
        return remainingMillis(deadlineNanos, System.nanoTime());
    }

    static long remainingMillis(long deadlineNanos, long currentNanos) {
        long remainingNanos = remainingNanos(deadlineNanos, currentNanos);
        if (remainingNanos <= 0) {
            return 0;
        }
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    static long saturatedMillis(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        try {
            return duration.toMillis();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    static long remainingNanos(long deadlineNanos, long currentNanos) {
        return deadlineNanos - currentNanos;
    }
}
