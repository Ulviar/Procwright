/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Owns ordinary and deadline-bounded process liveness semantics. */
final class ProcessLiveness {

    private static final String PROCESS_LIVENESS_OPERATION = "procwright-provider-liveness-";
    private static final String HANDLE_LIVENESS_OPERATION = "procwright-provider-handle-liveness-";
    private static final String EXIT_VALUE_OPERATION = "procwright-provider-exit-";

    private ProcessLiveness() {}

    static boolean hasExited(Process process) {
        try {
            return !process.isAlive();
        } catch (SecurityException | UnsupportedOperationException livenessUnavailable) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                return false;
            } catch (SecurityException | UnsupportedOperationException exitUnavailable) {
                return false;
            }
        }
    }

    static Observation observe(Process process, long lifecycleDeadlineNanos) throws InterruptedException {
        if (process instanceof GuardedProcess guarded) {
            try {
                return observe(guarded, lifecycleDeadlineNanos);
            } catch (SecurityException | UnsupportedOperationException unavailable) {
                return Observation.UNOBSERVABLE;
            }
        }
        try {
            return process.isAlive() ? Observation.LIVE : Observation.EXITED;
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return Observation.UNOBSERVABLE;
        }
    }

    static Observation observe(ProcessHandle handle, long lifecycleDeadlineNanos) throws InterruptedException {
        if (handle instanceof GuardedProcessHandle guarded) {
            try {
                return observe(guarded, lifecycleDeadlineNanos);
            } catch (SecurityException | UnsupportedOperationException unavailable) {
                return Observation.UNOBSERVABLE;
            }
        }
        try {
            return handle.isAlive() ? Observation.LIVE : Observation.EXITED;
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return Observation.UNOBSERVABLE;
        }
    }

    static Observation observeExit(GuardedProcess process, LivenessObservationBudget budget)
            throws InterruptedException {
        try {
            return observe(process, budget);
        } catch (SecurityException | UnsupportedOperationException livenessUnavailable) {
            return observeGuardedExitValue(process, budget);
        }
    }

    static ExitObservation observeExitForCleanup(Process process, long lifecycleDeadlineNanos) {
        List<Throwable> events = new ArrayList<>(2);
        if (process instanceof GuardedProcess guarded) {
            LivenessObservationBudget budget = LivenessObservationBudget.untilLifecycleDeadline(
                    lifecycleDeadlineNanos, guarded.providerOperationTimeout());
            try {
                return new ExitObservation(observe(guarded, budget), events);
            } catch (SecurityException | UnsupportedOperationException unavailable) {
                return observeGuardedExitValueForCleanup(guarded, budget, events);
            } catch (InterruptedException interruption) {
                events.add(interruption);
                return observeGuardedExitValueForCleanup(guarded, budget, events);
            } catch (RuntimeException | Error livenessFailure) {
                events.add(livenessFailure);
                return observeGuardedExitValueForCleanup(guarded, budget, events);
            }
        }
        try {
            Observation observation = observe(process, lifecycleDeadlineNanos);
            return observation == Observation.UNOBSERVABLE
                    ? observeExitValueForCleanup(process, lifecycleDeadlineNanos, events)
                    : new ExitObservation(observation, events);
        } catch (InterruptedException interruption) {
            events.add(interruption);
            return observeExitValueForCleanup(process, lifecycleDeadlineNanos, events);
        } catch (RuntimeException | Error livenessFailure) {
            events.add(livenessFailure);
            return observeExitValueForCleanup(process, lifecycleDeadlineNanos, events);
        }
    }

    static Observation observe(GuardedProcess process, long lifecycleDeadlineNanos) throws InterruptedException {
        return observe(
                process,
                LivenessObservationBudget.untilLifecycleDeadline(
                        lifecycleDeadlineNanos, process.providerOperationTimeout()));
    }

    static Observation observe(GuardedProcess process, LivenessObservationBudget budget) throws InterruptedException {
        Optional<Duration> remaining = budget.remainingOperationBudget(PROCESS_LIVENESS_OPERATION);
        if (remaining.isEmpty()) {
            return Observation.UNKNOWN;
        }
        try {
            return process.isAliveWithin(remaining.orElseThrow()) ? Observation.LIVE : Observation.EXITED;
        } catch (CommandExecutionException failure) {
            if (ProcessTreeScanner.causedByOperationDeadline(failure) && budget.lifecycleLimited()) {
                return Observation.UNKNOWN;
            }
            throw failure;
        }
    }

    static Observation observe(GuardedProcessHandle handle, long lifecycleDeadlineNanos) throws InterruptedException {
        LivenessObservationBudget budget = LivenessObservationBudget.untilLifecycleDeadline(
                lifecycleDeadlineNanos, handle.providerOperationTimeout());
        Optional<Duration> remaining = budget.remainingOperationBudget(HANDLE_LIVENESS_OPERATION);
        if (remaining.isEmpty()) {
            return Observation.UNKNOWN;
        }
        try {
            return handle.isAliveWithin(remaining.orElseThrow()) ? Observation.LIVE : Observation.EXITED;
        } catch (CommandExecutionException failure) {
            if (ProcessTreeScanner.causedByOperationDeadline(failure) && budget.lifecycleLimited()) {
                return Observation.UNKNOWN;
            }
            throw failure;
        }
    }

    private static Observation observeExitValue(Process process, long lifecycleDeadlineNanos)
            throws InterruptedException {
        try {
            if (process instanceof GuardedProcess guarded) {
                long remainingNanos = lifecycleDeadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return Observation.UNKNOWN;
                }
                guarded.exitValueWithin(Duration.ofNanos(remainingNanos));
            } else {
                process.exitValue();
            }
            return Observation.EXITED;
        } catch (IllegalThreadStateException stillRunning) {
            return Observation.LIVE;
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return Observation.UNOBSERVABLE;
        }
    }

    private static Observation observeGuardedExitValue(GuardedProcess process, LivenessObservationBudget budget)
            throws InterruptedException {
        Optional<Duration> remaining = budget.remainingOperationBudget(EXIT_VALUE_OPERATION);
        if (remaining.isEmpty()) {
            return Observation.UNKNOWN;
        }
        try {
            process.exitValueWithin(remaining.orElseThrow());
            return Observation.EXITED;
        } catch (IllegalThreadStateException stillRunning) {
            return Observation.LIVE;
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return Observation.UNOBSERVABLE;
        } catch (CommandExecutionException failure) {
            if (ProcessTreeScanner.causedByOperationDeadline(failure) && budget.lifecycleLimited()) {
                return Observation.UNKNOWN;
            }
            throw failure;
        }
    }

    private static ExitObservation observeGuardedExitValueForCleanup(
            GuardedProcess process, LivenessObservationBudget budget, List<Throwable> events) {
        try {
            return new ExitObservation(observeGuardedExitValue(process, budget), events);
        } catch (InterruptedException interruption) {
            events.add(interruption);
            return new ExitObservation(Observation.UNKNOWN, events);
        } catch (RuntimeException | Error failure) {
            events.add(failure);
            return new ExitObservation(Observation.UNOBSERVABLE, events);
        }
    }

    private static ExitObservation observeExitValueForCleanup(
            Process process, long lifecycleDeadlineNanos, List<Throwable> events) {
        try {
            return new ExitObservation(observeExitValue(process, lifecycleDeadlineNanos), events);
        } catch (InterruptedException interruption) {
            events.add(interruption);
            return new ExitObservation(Observation.UNKNOWN, events);
        } catch (RuntimeException | Error failure) {
            events.add(failure);
            return new ExitObservation(Observation.UNOBSERVABLE, events);
        }
    }

    // UNKNOWN is exhausted lifecycle time. UNOBSERVABLE is unavailable OS/provider state. Neither proves exit.
    enum Observation {
        LIVE,
        EXITED,
        UNKNOWN,
        UNOBSERVABLE
    }

    record ExitObservation(Observation state, List<Throwable> events) {

        ExitObservation {
            events = List.copyOf(events);
        }
    }
}
