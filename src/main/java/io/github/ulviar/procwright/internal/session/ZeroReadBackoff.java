/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@FunctionalInterface
interface ZeroReadBackoff {

    boolean pause(int consecutiveZeroReads, BooleanSupplier closed);

    static ZeroReadBackoff exponential() {
        return ExponentialZeroReadBackoff.INSTANCE;
    }
}

final class ExponentialZeroReadBackoff implements ZeroReadBackoff {

    static final ExponentialZeroReadBackoff INSTANCE = new ExponentialZeroReadBackoff();

    private static final int MAXIMUM_SHIFT = 7;
    private static final long INITIAL_DELAY_NANOS = TimeUnit.MICROSECONDS.toNanos(100);
    private static final long MAXIMUM_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private ExponentialZeroReadBackoff() {}

    @Override
    public boolean pause(int consecutiveZeroReads, BooleanSupplier closed) {
        if (consecutiveZeroReads <= 0) {
            throw new IllegalArgumentException("consecutiveZeroReads must be positive");
        }
        BooleanSupplier closedState = Objects.requireNonNull(closed, "closed");
        if (closedState.getAsBoolean()) {
            return false;
        }
        int shift = Math.min(MAXIMUM_SHIFT, consecutiveZeroReads - 1);
        long delayNanos = Math.min(MAXIMUM_DELAY_NANOS, INITIAL_DELAY_NANOS << shift);
        try {
            TimeUnit.NANOSECONDS.sleep(delayNanos);
            return !closedState.getAsBoolean();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
