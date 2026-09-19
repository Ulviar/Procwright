/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Waits on the caller thread while preserving live descendants for later cleanup. */
final class ProcessExitWaiter {

    private static final ProcessTreeScanner PROCESS_TREE_SCANNER = ProcessTreeScanner.shared();
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private ProcessExitWaiter() {}

    static boolean waitFor(Process process, Duration timeout, LiveDescendantSnapshot descendants)
            throws InterruptedException {
        return waitFor(process, timeout, descendants, System::nanoTime);
    }

    static boolean waitFor(Process process, Duration timeout, LiveDescendantSnapshot descendants, LongSupplier nanoTime)
            throws InterruptedException {
        Objects.requireNonNull(nanoTime, "nanoTime");
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(descendants, "descendants");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        boolean unbounded = timeout.isZero();
        long deadlineNanos = unbounded ? 0 : DurationSupport.deadlineFrom(nanoTime.getAsLong(), timeout);
        while (true) {
            long remainingNanos = unbounded ? POLL_NANOS : deadlineNanos - nanoTime.getAsLong();
            if (remainingNanos <= 0) {
                return ProcessLiveness.hasExited(process);
            }
            if (ProcessLiveness.hasExited(process)) {
                return true;
            }
            remainingNanos = unbounded ? POLL_NANOS : deadlineNanos - nanoTime.getAsLong();
            if (remainingNanos <= 0) {
                return false;
            }
            Duration scanBudget = unbounded ? PROCESS_TREE_SCANNER.scanTimeout() : Duration.ofNanos(remainingNanos);
            descendants.refresh(process, scanBudget);
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while observing process descendants");
            }
            remainingNanos = unbounded ? POLL_NANOS : deadlineNanos - nanoTime.getAsLong();
            if (remainingNanos <= 0) {
                return ProcessLiveness.hasExited(process);
            }
            long waitNanos = Math.min(remainingNanos, POLL_NANOS);
            if (process.waitFor(waitNanos, TimeUnit.NANOSECONDS)) {
                return true;
            }
        }
    }
}
