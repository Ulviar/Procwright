/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Selects the first signal that tells a one-shot execution to stop waiting for its process. */
final class OneShotSupervision {

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final Process process;
    private final OneShotDeadline deadline;
    private final LiveDescendantSnapshot liveDescendants;
    private final AtomicReference<Signal> signal = new AtomicReference<>();

    OneShotSupervision(Process process, OneShotDeadline deadline, LiveDescendantSnapshot liveDescendants) {
        this.process = Objects.requireNonNull(process, "process");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.liveDescendants = Objects.requireNonNull(liveDescendants, "liveDescendants");
    }

    Consumer<Throwable> stdinFailureHandler() {
        AtomicReference<Signal> winner = signal;
        return failure -> claim(winner, new StdinFailure(failure));
    }

    Consumer<Throwable> outputFailureHandler() {
        AtomicReference<Signal> winner = signal;
        return failure -> claim(winner, new OutputFailure(failure));
    }

    Signal await() throws InterruptedException {
        while (true) {
            Signal selected = signal.get();
            if (selected != null) {
                return selected;
            }
            long remainingNanos = deadline.bounded() ? deadline.remainingNanos() : POLL_NANOS;
            if (remainingNanos <= 0) {
                claim(signal, new TimedOut());
                return signal.get();
            }
            try {
                boolean exited = ProcessLifecycle.waitFor(
                        process, Duration.ofNanos(Math.min(remainingNanos, POLL_NANOS)), liveDescendants);
                if (exited) {
                    claim(signal, new ProcessExited());
                }
            } catch (InterruptedException interruption) {
                if (signal.get() != null) {
                    Thread.currentThread().interrupt();
                    return signal.get();
                }
                throw interruption;
            }
        }
    }

    private static void claim(AtomicReference<Signal> winner, Signal candidate) {
        winner.compareAndSet(null, Objects.requireNonNull(candidate, "candidate"));
    }

    sealed interface Signal permits ProcessExited, TimedOut, StdinFailure, OutputFailure {}

    record ProcessExited() implements Signal {}

    record TimedOut() implements Signal {}

    record StdinFailure(Throwable failure) implements Signal {

        StdinFailure {
            Objects.requireNonNull(failure, "failure");
        }
    }

    record OutputFailure(Throwable failure) implements Signal {

        OutputFailure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
