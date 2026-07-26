/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One absolute deadline shared by every blocking phase of a one-shot execution. */
final class OneShotDeadline {

    private final boolean bounded;
    private final long deadlineNanos;

    private OneShotDeadline(boolean bounded, long deadlineNanos) {
        this.bounded = bounded;
        this.deadlineNanos = deadlineNanos;
    }

    static OneShotDeadline start(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return timeout.isZero()
                ? new OneShotDeadline(false, 0)
                : new OneShotDeadline(true, DurationSupport.deadlineFromNow(timeout));
    }

    boolean bounded() {
        return bounded;
    }

    long remainingNanos() {
        return bounded ? deadlineNanos - System.nanoTime() : Long.MAX_VALUE;
    }

    <T> T await(Future<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(future, "future");
        if (!bounded) {
            return future.get();
        }
        return future.get(Math.max(0, remainingNanos()), TimeUnit.NANOSECONDS);
    }
}
