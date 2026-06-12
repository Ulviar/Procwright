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
}
