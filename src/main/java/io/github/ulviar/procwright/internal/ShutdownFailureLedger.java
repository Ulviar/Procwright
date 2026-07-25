/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.util.ArrayList;
import java.util.List;

/** Retains cleanup failures and interruption state for one shutdown operation without mutating source failures. */
final class ShutdownFailureLedger {

    private List<Throwable> failures;
    private Throwable firstPrimary;
    private CommandExecutionException interruptionFailure;
    private boolean restoreInterrupt;

    void attempt(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            record(failure);
        } finally {
            interruptionBoundary();
        }
    }

    void record(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (firstPrimary == null) {
            firstPrimary = FailureAggregation.primary(failure);
        }
        for (Throwable source : FailureAggregation.sources(failure)) {
            recordSource(source);
        }
    }

    private void recordSource(Throwable failure) {
        if (failures != null) {
            for (Throwable observed : failures) {
                if (observed == failure) {
                    return;
                }
            }
        } else {
            failures = new ArrayList<>(2);
        }
        failures.add(failure);
    }

    void recordObserved(Iterable<? extends Throwable> observed) {
        for (Throwable event : observed) {
            if (event instanceof InterruptedException interruption) {
                interrupted(interruption);
            } else {
                record(event);
            }
        }
    }

    void interrupted(InterruptedException interruption) {
        interrupted(new CommandExecutionException("Interrupted while waiting for command cleanup", interruption));
    }

    void interruptionBoundary() {
        if (Thread.interrupted()) {
            interrupted(new InterruptedException("cleanup boundary observed interrupt status"));
        }
    }

    boolean hasFailure() {
        return failures != null;
    }

    boolean wasInterrupted() {
        return restoreInterrupt;
    }

    void rethrowIfPresent() {
        interruptionBoundary();
        Throwable primary = failure();
        if (primary instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (primary instanceof Error error) {
            throw error;
        }
        if (primary != null) {
            throw new AssertionError("cleanup failure must be unchecked", primary);
        }
    }

    void restoreInterrupt() {
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }

    private void interrupted(CommandExecutionException interruptionFailure) {
        if (!restoreInterrupt) {
            this.interruptionFailure = interruptionFailure;
        }
        record(interruptionFailure);
        restoreInterrupt = true;
        Thread.interrupted();
    }

    private Throwable failure() {
        if (failures == null) {
            return null;
        }
        Throwable primary = interruptionFailure == null ? firstPrimary : interruptionFailure;
        if (primary instanceof Error) {
            return FailureAggregation.combineWithPrimary(
                    primary, failures, "Multiple process shutdown operations failed");
        }
        if (failures.size() == 1 && primary instanceof CommandExecutionException) {
            return primary;
        }
        CommandExecutionException envelope =
                new CommandExecutionException("One or more process shutdown operations failed", primary);
        for (Throwable failure : failures) {
            if (failure != primary) {
                envelope.addSuppressed(failure);
            }
        }
        return envelope;
    }
}
