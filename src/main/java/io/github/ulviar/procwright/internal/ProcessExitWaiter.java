/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Waits on the caller thread while preserving live descendants for later cleanup. */
final class ProcessExitWaiter {

    private static final ProcessTreeScanner PROCESS_TREE_SCANNER = ProcessTreeScanner.shared();
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final PollClock SYSTEM_POLL_CLOCK = new SystemPollClock();

    private ProcessExitWaiter() {}

    static boolean waitFor(Process process, Duration timeout, LiveDescendantSnapshot descendants)
            throws InterruptedException {
        return waitFor(process, timeout, descendants, SYSTEM_POLL_CLOCK);
    }

    static boolean waitFor(Process process, Duration timeout, LiveDescendantSnapshot descendants, PollClock clock)
            throws InterruptedException {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(descendants, "descendants");
        Objects.requireNonNull(clock, "clock");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        boolean unbounded = timeout.isZero();
        boolean guarded = process instanceof GuardedProcess;
        long deadlineNanos = unbounded ? 0 : DurationSupport.deadlineFrom(clock.nanoTime(), timeout);
        while (true) {
            long remainingNanos = unbounded ? POLL_NANOS : deadlineNanos - clock.nanoTime();
            if (remainingNanos <= 0) {
                return guarded ? false : ProcessLiveness.hasExited(process);
            }
            if (guarded) {
                GuardedProcess guardedProcess = (GuardedProcess) process;
                LivenessObservationBudget budget = unbounded
                        ? LivenessObservationBudget.providerLimited(guardedProcess.providerOperationTimeout())
                        : LivenessObservationBudget.fromRemainingLifecycle(
                                Duration.ofNanos(remainingNanos), guardedProcess.providerOperationTimeout());
                ProcessLiveness.Observation observation = ProcessLiveness.observeExit(guardedProcess, budget);
                if (observation == ProcessLiveness.Observation.EXITED) {
                    return true;
                }
                if (observation == ProcessLiveness.Observation.UNKNOWN) {
                    return false;
                }
            } else if (ProcessLiveness.hasExited(process)) {
                return true;
            }
            remainingNanos = unbounded ? POLL_NANOS : deadlineNanos - clock.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            Duration scanBudget = unbounded ? PROCESS_TREE_SCANNER.scanTimeout() : Duration.ofNanos(remainingNanos);
            if (unbounded) {
                descendants.refreshWithFreshLivenessBudget(process, scanBudget);
            } else {
                descendants.refresh(process, scanBudget, deadlineNanos);
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while observing process descendants");
            }
            remainingNanos = unbounded ? POLL_NANOS : deadlineNanos - clock.nanoTime();
            if (remainingNanos <= 0) {
                return guarded ? false : ProcessLiveness.hasExited(process);
            }
            long waitNanos = Math.min(remainingNanos, POLL_NANOS);
            if (guarded) {
                long sleepNanos = waitNanos < POLL_NANOS ? Math.max(1, waitNanos / 2) : waitNanos;
                clock.sleep(sleepNanos);
            } else if (process.waitFor(waitNanos, TimeUnit.NANOSECONDS)) {
                return true;
            }
        }
    }

    interface PollClock {
        long nanoTime();

        void sleep(long nanos) throws InterruptedException;
    }

    private static final class SystemPollClock implements PollClock {

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleep(long nanos) throws InterruptedException {
            TimeUnit.NANOSECONDS.sleep(nanos);
        }
    }
}
