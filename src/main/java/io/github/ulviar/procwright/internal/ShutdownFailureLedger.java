/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;

/** Preserves cleanup failure identity, suppression order, and interruption state for one shutdown operation. */
final class ShutdownFailureLedger {

    private Throwable primary;
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
        if (failure instanceof CommandExecutionException executionFailure
                && SuppressionSupport.containsInterruption(executionFailure)) {
            interrupted(executionFailure);
            return;
        }
        primary = SuppressionSupport.combine(primary, failure);
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
        return primary != null;
    }

    boolean wasInterrupted() {
        return restoreInterrupt;
    }

    void rethrowIfPresent() {
        interruptionBoundary();
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
            Throwable previousPrimary = primary;
            primary = interruptionFailure;
            SuppressionSupport.attach(primary, previousPrimary);
        } else {
            SuppressionSupport.attach(primary, interruptionFailure);
        }
        restoreInterrupt = true;
        Thread.interrupted();
    }
}
