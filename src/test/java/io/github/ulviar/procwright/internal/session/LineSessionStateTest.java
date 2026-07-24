/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import org.junit.jupiter.api.Test;

final class LineSessionStateTest {

    @Test
    void closeWinsOverRetrySafeFailureSelectedAfterClose() {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("diagnostic", false, false));
        RequestFailureTracker<LineSessionException> request = state.beginRequest();

        state.claimClose();

        LineSessionException failure = assertThrows(
                LineSessionException.class, () -> state.releaseRetryablePreWrite(request, state.timeout()));
        assertEquals(LineSessionException.Reason.CLOSED, failure.reason());
        assertEquals("diagnostic", failure.transcript().text());
    }

    @Test
    void fatalFailureRemainsPrimaryWhenRequestFailureArrivesLater() {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        RequestFailureTracker<LineSessionException> request = state.beginRequest();
        AssertionError fatal = new AssertionError("fatal output");
        LineSessionException responseLimit =
                state.failure(LineSessionException.Reason.RESPONSE_TOO_LARGE, "response limit", null);

        state.recordFatalError(fatal);
        LineSessionException selected = state.recordRequestFailure(request, () -> responseLimit);

        assertSame(responseLimit, selected);
        assertSame(fatal, state.terminal().fatalError());
        assertSame(responseLimit, fatal.getSuppressed()[0]);
    }

    @Test
    void requestFailureRemainsPrimaryWhenFatalFailureArrivesLater() {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        RequestFailureTracker<LineSessionException> request = state.beginRequest();
        LineSessionException responseLimit =
                state.failure(LineSessionException.Reason.RESPONSE_TOO_LARGE, "response limit", null);
        AssertionError fatal = new AssertionError("fatal output");

        state.recordRequestFailure(request, () -> responseLimit);
        LineSessionState.TerminalSnapshot selected = state.recordFatalError(fatal);

        assertSame(responseLimit, selected.primary());
        assertNull(selected.fatalError());
        assertSame(fatal, responseLimit.getSuppressed()[0]);
    }
}
