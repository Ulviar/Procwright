/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Selects the first one-shot terminal outcome without letting concurrent interruption replace it. */
final class OneShotTermination {

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final Process process;
    private final Duration timeout;
    private final AtomicReference<Set<ProcessHandle>> liveDescendants;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>();

    OneShotTermination(Process process, Duration timeout, AtomicReference<Set<ProcessHandle>> liveDescendants) {
        this.process = Objects.requireNonNull(process, "process");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.liveDescendants = Objects.requireNonNull(liveDescendants, "liveDescendants");
    }

    Consumer<Throwable> stdinFailureHandler() {
        AtomicReference<Outcome> winner = outcome;
        return failure -> claim(winner, new StdinFailure(failure));
    }

    Outcome await() throws InterruptedException {
        boolean unbounded = timeout.isZero();
        long deadlineNanos = unbounded ? 0 : DurationSupport.deadlineFromNow(timeout);
        while (true) {
            Outcome selected = outcome.get();
            if (selected != null) {
                return selected;
            }
            long remainingNanos = unbounded ? POLL_NANOS : deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                claim(outcome, new TimedOut());
                return outcome.get();
            }
            try {
                boolean exited = ProcessLifecycle.waitFor(
                        process, Duration.ofNanos(Math.min(remainingNanos, POLL_NANOS)), liveDescendants);
                if (exited) {
                    claim(outcome, new ProcessExited());
                }
            } catch (InterruptedException interruption) {
                if (outcome.get() != null) {
                    Thread.currentThread().interrupt();
                    return outcome.get();
                }
                throw interruption;
            }
        }
    }

    private static void claim(AtomicReference<Outcome> winner, Outcome candidate) {
        winner.compareAndSet(null, Objects.requireNonNull(candidate, "candidate"));
    }

    sealed interface Outcome permits ProcessExited, TimedOut, StdinFailure {}

    record ProcessExited() implements Outcome {}

    record TimedOut() implements Outcome {}

    record StdinFailure(Throwable failure) implements Outcome {

        StdinFailure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
