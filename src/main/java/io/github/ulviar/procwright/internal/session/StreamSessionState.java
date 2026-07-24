/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Owns stream-session terminal arbitration and the conditions for one terminal publication.
 *
 * <p>A terminal completion becomes publishable only after both output pumps finish. A failure does not wait for the
 * nested raw-session exit because selecting it already starts raw-session cleanup. Non-failure outcomes also require a
 * nested raw-session terminal snapshot.
 */
final class StreamSessionState {

    private int outputPumpsRemaining;
    private Outcome outcome;
    private NestedTerminal nestedTerminal;
    private boolean completionClaimed;
    private volatile boolean stopping;

    StreamSessionState(int outputPumpCount) {
        if (outputPumpCount <= 0) {
            throw new IllegalArgumentException("outputPumpCount must be positive");
        }
        outputPumpsRemaining = outputPumpCount;
    }

    boolean stopping() {
        return stopping;
    }

    synchronized boolean hasOutcome() {
        return outcome != null;
    }

    synchronized boolean controlledStop() {
        return outcome == Control.CLOSED || outcome == Control.TIMED_OUT;
    }

    synchronized boolean selectControl(Control candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (outcome != null) {
            return false;
        }
        outcome = candidate;
        stopping = true;
        return true;
    }

    synchronized FailureSelection selectFailure(Throwable candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (outcome == null) {
            outcome = new FailureOutcome(candidate);
            stopping = true;
            return new FailureSelection(true, candidate, null, null);
        }
        if (outcome instanceof FailureOutcome failureOutcome) {
            return new FailureSelection(false, failureOutcome.primary(), candidate, null);
        }
        return new FailureSelection(false, null, null, candidate);
    }

    synchronized void nestedSucceeded(SessionExit exit) {
        recordNestedTerminal(NestedTerminal.succeeded(exit));
    }

    synchronized void nestedFailed(Throwable failure) {
        recordNestedTerminal(NestedTerminal.failed(failure));
    }

    synchronized boolean outputPumpCompleted() {
        if (outputPumpsRemaining == 0) {
            throw new IllegalStateException("all stream output pumps are already complete");
        }
        outputPumpsRemaining--;
        return outputPumpsRemaining == 0;
    }

    synchronized Completion claimCompletion() {
        if (completionClaimed || outputPumpsRemaining != 0) {
            return null;
        }
        if (outcome instanceof FailureOutcome failureOutcome) {
            completionClaimed = true;
            return new FailedCompletion(failureOutcome.primary());
        }
        if (nestedTerminal == null) {
            return null;
        }
        if (outcome == null) {
            if (nestedTerminal.failure() != null) {
                return null;
            }
            outcome = Normal.INSTANCE;
            stopping = true;
        }
        completionClaimed = true;
        return new SuccessfulCompletion(
                nestedTerminal.exitCode(),
                outcome == Control.TIMED_OUT || nestedTerminal.timedOut(),
                outcome == Control.CLOSED);
    }

    synchronized void stop() {
        stopping = true;
    }

    private void recordNestedTerminal(NestedTerminal terminal) {
        if (nestedTerminal != null) {
            throw new IllegalStateException("nested stream session terminal is already recorded");
        }
        nestedTerminal = terminal;
    }

    enum Control implements Outcome {
        TIMED_OUT,
        CLOSED
    }

    sealed interface Completion permits SuccessfulCompletion, FailedCompletion {}

    record SuccessfulCompletion(OptionalInt exitCode, boolean timedOut, boolean closed) implements Completion {

        SuccessfulCompletion {
            Objects.requireNonNull(exitCode, "exitCode");
        }
    }

    record FailedCompletion(Throwable primary) implements Completion {

        FailedCompletion {
            Objects.requireNonNull(primary, "primary");
        }
    }

    record FailureSelection(boolean installed, Throwable primary, Throwable suppressedFailure, Throwable lateFailure) {

        FailureSelection {
            if (installed) {
                if (primary == null || suppressedFailure != null || lateFailure != null) {
                    throw new IllegalArgumentException("an installed failure must be the sole primary");
                }
            } else if (primary != null) {
                if (suppressedFailure == null || lateFailure != null) {
                    throw new IllegalArgumentException("a canonical failure must own one suppressed failure");
                }
            } else if (suppressedFailure != null || lateFailure == null) {
                throw new IllegalArgumentException("a control outcome must expose one late failure");
            }
        }

        void attachSuppressedFailure() {
            if (suppressedFailure != null) {
                SuppressionSupport.attach(primary, suppressedFailure);
            }
        }
    }

    private sealed interface Outcome permits Normal, Control, FailureOutcome {}

    private enum Normal implements Outcome {
        INSTANCE
    }

    private record FailureOutcome(Throwable primary) implements Outcome {

        private FailureOutcome {
            Objects.requireNonNull(primary, "primary");
        }
    }

    private record NestedTerminal(SessionExit exit, Throwable failure) {

        private NestedTerminal {
            if ((exit == null) == (failure == null)) {
                throw new IllegalArgumentException("nested terminal must contain either exit or failure");
            }
        }

        private static NestedTerminal succeeded(SessionExit exit) {
            return new NestedTerminal(Objects.requireNonNull(exit, "exit"), null);
        }

        private static NestedTerminal failed(Throwable failure) {
            return new NestedTerminal(null, Objects.requireNonNull(failure, "failure"));
        }

        private OptionalInt exitCode() {
            return exit == null ? OptionalInt.empty() : exit.exitCode();
        }

        private boolean timedOut() {
            return exit != null && exit.timedOut();
        }
    }
}
