/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class DurationSupport {

    private DurationSupport() {}

    public static long saturatedNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public static long deadlineFromNow(Duration duration) {
        return deadlineFrom(System.nanoTime(), duration);
    }

    public static long deadlineFrom(long currentNanos, Duration duration) {
        return currentNanos + saturatedNanos(duration);
    }

    public static long remainingMillis(long deadlineNanos) {
        return remainingMillis(deadlineNanos, System.nanoTime());
    }

    public static long remainingMillis(long deadlineNanos, long currentNanos) {
        long remainingNanos = remainingNanos(deadlineNanos, currentNanos);
        if (remainingNanos <= 0) {
            return 0;
        }
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    public static long saturatedMillis(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        try {
            return duration.toMillis();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public static long remainingNanos(long deadlineNanos, long currentNanos) {
        return deadlineNanos - currentNanos;
    }

    public static Duration elapsed(long startedNanos, long finishedNanos) {
        long elapsedNanos = finishedNanos - startedNanos;
        if (finishedNanos >= startedNanos && elapsedNanos < 0) {
            return Duration.ofNanos(Long.MAX_VALUE);
        }
        if (elapsedNanos < 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(elapsedNanos);
    }

    public static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }

    public static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
