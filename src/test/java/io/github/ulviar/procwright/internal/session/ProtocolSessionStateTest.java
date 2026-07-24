/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class ProtocolSessionStateTest {

    @Test
    void terminalFailureSelectedBeforeCloseRemainsCanonical() {
        ProtocolSessionState state = state();
        IllegalStateException cause = new IllegalStateException("overflow");

        state.recordTerminalFailure(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "overflow", cause);
        ProtocolSessionState.CloseDecision close = state.claimClose(true);

        assertEquals(ProtocolSessionState.TerminalKind.FAILURE, close.terminal().kind());
        assertSame(cause, close.terminal().primary());
        ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, state::ensureOpen);
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, followUp.reason());
        assertSame(cause, followUp.getCause());
    }

    @Test
    void closeSelectedBeforeNonfatalFailureRemainsCanonical() {
        ProtocolSessionState state = state();
        IllegalStateException late = new IllegalStateException("late");

        state.claimClose(true);
        ProtocolSessionState.TerminalSnapshot selected =
                state.recordTerminalFailure(ProtocolSessionException.Reason.FAILURE, "late", late);

        assertTrue(selected.isClosed());
        ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, state::ensureOpen);
        assertEquals(ProtocolSessionException.Reason.CLOSED, followUp.reason());
        assertEquals(0, followUp.getSuppressed().length);
    }

    @Test
    void fatalFailurePromotesClosedAndRetainsActiveClosedFailure() {
        ProtocolSessionState state = state();
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        ProtocolSessionException closed = state.recordRequestFailure(request, () -> state.closed(null));
        AssertionError fatal = new AssertionError("fatal");

        state.claimClose(true);
        ProtocolSessionState.TerminalSnapshot selected = state.recordFatalError(fatal);

        assertSame(fatal, selected.fatalError());
        assertSame(closed, fatal.getSuppressed()[0]);
        assertSame(fatal, assertThrows(AssertionError.class, state::ensureOpen));
    }

    @Test
    void timeoutFailureIsCanonicalPerRequest() {
        ProtocolSessionState state = state();
        ProtocolSessionState.RequestOutcome request = state.beginRequest();

        ProtocolSessionException first = state.recordRequestTimeout(request);
        ProtocolSessionException second = state.recordRequestTimeout(request);

        assertSame(first, second);
        assertEquals(ProtocolSessionException.Reason.TIMEOUT, first.reason());
    }

    @Test
    void fatalFailureWinsWhetherItArrivesBeforeOrAfterTimeout() {
        for (boolean fatalFirst : new boolean[] {true, false}) {
            ProtocolSessionState state = state();
            ProtocolSessionState.RequestOutcome request = state.beginRequest();
            AssertionError fatal = new AssertionError("fatal-" + fatalFirst);
            ProtocolSessionException timeout;
            if (fatalFirst) {
                state.recordFatalError(fatal);
                timeout = state.recordRequestTimeout(request);
            } else {
                timeout = state.recordRequestTimeout(request);
                state.recordFatalError(fatal);
            }

            ProtocolSessionState.TerminalSnapshot selected = state.terminal();
            assertSame(fatal, selected.fatalError());
            assertTrue(containsIdentity(fatal.getSuppressed(), timeout));
        }
    }

    @Test
    void terminalFailureReplacesEarlierActiveClosedFailure() {
        ProtocolSessionState state = state();
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        state.recordRequestFailure(request, () -> state.closed(null));
        IllegalStateException overflow = new IllegalStateException("overflow");

        state.recordTerminalFailure(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "overflow", overflow);

        ProtocolSessionException selected = request.failure();
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, selected.reason());
        assertSame(overflow, selected.getCause());
    }

    @Test
    void eofSealsAttributionOnlyAfterActiveRequestEnds() {
        ProtocolSessionState state = state();
        ProtocolSessionState.RequestOutcome request = state.beginRequest();

        assertFalse(state.recordStdoutEof());
        state.completeRequest(request);
        assertTrue(state.endRequest(request));
    }

    @Test
    void staleRequestCannotCompleteOrEndTheActiveRequest() {
        ProtocolSessionState state = state();
        ProtocolSessionState.RequestOutcome stale = state.beginRequest();
        assertFalse(state.endRequest(stale));
        ProtocolSessionState.RequestOutcome active = state.beginRequest();
        assertFalse(state.recordStdoutEof());

        assertThrows(IllegalStateException.class, () -> state.completeRequest(stale));
        assertFalse(state.endRequest(stale));
        state.completeRequest(active);
        assertTrue(state.endRequest(active));
    }

    @Test
    void lateCallbackFailureAttachesToSelectedRequestFailure() {
        ProtocolSessionState state = state();
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        ProtocolSessionException timeout = state.recordRequestTimeout(request);
        IllegalStateException late = new IllegalStateException("late");

        state.attachLateCallbackFailure(request, late);

        assertSame(late, timeout.getSuppressed()[0]);
    }

    @Test
    void eofUsesObservedProcessExitWhenAvailable() {
        ProtocolSessionState state =
                new ProtocolSessionState(() -> new ProtocolTranscript("", false, false), () -> OptionalInt.of(17));

        ProtocolSessionException failure = state.eof();

        assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, failure.reason());
        assertEquals(17, failure.exitCode().orElseThrow());
    }

    @Test
    void terminalStateOverridesLocalRequestAdmissionFailure() {
        ProtocolSessionState closed = state();
        closed.claimClose(true);
        ProtocolSessionException closedFailure = assertThrows(
                ProtocolSessionException.class,
                () -> closed.arbitrateRequestAdmissionFailure(() -> closed.timeout(null)));
        assertEquals(ProtocolSessionException.Reason.CLOSED, closedFailure.reason());

        ProtocolSessionState failed = state();
        IllegalStateException cause = new IllegalStateException("overflow");
        failed.recordTerminalFailure(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "overflow", cause);
        ProtocolSessionException terminalFailure = assertThrows(
                ProtocolSessionException.class,
                () -> failed.arbitrateRequestAdmissionFailure(() -> failed.timeout(null)));
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, terminalFailure.reason());
        assertSame(cause, terminalFailure.getCause());

        ProtocolSessionState fatal = state();
        AssertionError fatalError = new AssertionError("fatal");
        fatal.recordFatalError(fatalError);
        assertSame(
                fatalError,
                assertThrows(
                        AssertionError.class, () -> fatal.arbitrateRequestAdmissionFailure(() -> fatal.timeout(null))));
    }

    private static ProtocolSessionState state() {
        return new ProtocolSessionState(() -> new ProtocolTranscript("diagnostic", false, false), OptionalInt::empty);
    }

    private static boolean containsIdentity(Throwable[] failures, Throwable expected) {
        for (Throwable failure : failures) {
            if (failure == expected) {
                return true;
            }
        }
        return false;
    }
}
