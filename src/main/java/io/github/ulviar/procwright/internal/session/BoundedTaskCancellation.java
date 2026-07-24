/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Gives one bounded execution an explicit cancellable or uncancellable policy. */
interface BoundedTaskCancellation {

    BoundedTaskPermit acquire(BoundedTaskLimiter limiter, long deadlineNanos, LongSupplier nanoTime)
            throws TimeoutException, InterruptedException, BoundedTaskRunner.TaskCancelledException;

    Registration register(Runnable listener);

    void throwIfCancelled() throws BoundedTaskRunner.TaskCancelledException;

    static BoundedTaskCancellation none() {
        return None.INSTANCE;
    }

    @FunctionalInterface
    interface Registration {

        void close();
    }

    enum None implements BoundedTaskCancellation {
        INSTANCE;

        @Override
        public BoundedTaskPermit acquire(BoundedTaskLimiter limiter, long deadlineNanos, LongSupplier nanoTime)
                throws TimeoutException, InterruptedException {
            return limiter.acquire(deadlineNanos, nanoTime);
        }

        @Override
        public Registration register(Runnable listener) {
            return () -> {};
        }

        @Override
        public void throwIfCancelled() {}
    }
}
