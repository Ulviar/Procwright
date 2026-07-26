/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LineSessionStateTest {

    @Test
    void terminalFailurePublishesACompleteTypedSnapshot() {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        IllegalStateException cause = new IllegalStateException("decode failed");

        LineSessionState.FailureSnapshot selected = (LineSessionState.FailureSnapshot)
                state.recordTerminalFailure(LineSessionException.Reason.DECODE_ERROR, "decode", cause);

        assertEquals(LineSessionException.Reason.DECODE_ERROR, selected.reason());
        assertEquals("decode", selected.message());
        assertSame(cause, selected.primary());
        assertSame(selected, state.terminal());
    }

    @Test
    void selectedTerminalFailureRejectsNewWorkBeforeCloseCleanupStarts() {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        IllegalStateException cause = new IllegalStateException("output failed");

        state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "output failed", cause);

        LineSessionException failure = assertThrows(LineSessionException.class, state::ensureOpen);
        assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
        assertSame(cause, failure.getCause());
    }

    @Test
    void successfulCompletionReleasesTheActiveRequest() {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        LineSessionState.Request completed = state.beginRequest();

        state.completeRequest(completed);

        LineSessionState.Request next = state.beginRequest();
        next.close();
    }

    @Test
    void closeWinsOverRetrySafeFailureSelectedAfterClose() {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("diagnostic", false, false));
        LineSessionState.Request request = state.beginRequest();

        state.claimClose();

        LineSessionException failure = assertThrows(
                LineSessionException.class, () -> state.releaseRetryablePreWrite(request, state.timeout()));
        assertEquals(LineSessionException.Reason.CLOSED, failure.reason());
        assertEquals("diagnostic", failure.transcript().text());
    }

    @Test
    void closeClaimRejectsEofBeforeTheTransportPublishesItsClosedEvent() {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        LineSessionState.Request request = state.beginRequest();

        assertTrue(state.claimClose());
        LineSessionException selected = state.recordRequestFailure(request, state::eof);

        assertEquals(LineSessionException.Reason.CLOSED, selected.reason());
        assertNull(state.terminal());
        assertTrue(discarded.isEmpty());
    }

    @Test
    void eofSelectedBeforeCloseRemainsTerminal() {
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false));
        LineSessionState.Request request = state.beginRequest();

        LineSessionException eof = state.recordRequestFailure(request, state::eof);
        assertTrue(state.claimClose());

        assertSame(eof, state.terminal().primary());
        LineSessionException selected = assertThrows(LineSessionException.class, () -> state.completeRequest(request));
        assertEquals(LineSessionException.Reason.EOF, selected.reason());
    }

    @Test
    void ordinaryOutputFailureAfterCloseIsDiscardedSilently() {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        IllegalStateException late = new IllegalStateException("late output");

        state.claimClose();
        LineSessionState.OutputSelection selection =
                state.recordOutputFailure(LineSessionException.Reason.FAILURE, "late", late);

        assertTrue(selection.rejectedAfterClose());
        assertNull(selection.selected());
        assertSame(late, selection.discarded());
        assertNull(state.terminal());
        assertTrue(discarded.isEmpty());
    }

    @Test
    void fatalOutputFailureAfterCloseIsOnlyReported() {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        AssertionError late = new AssertionError("late output");

        state.claimClose();
        LineSessionState.OutputSelection selection = state.recordOutputFatalError(late);

        assertTrue(selection.rejectedAfterClose());
        assertNull(selection.selected());
        assertSame(late, selection.discarded());
        assertNull(state.terminal());
        assertEquals(List.of(late), discarded);
    }

    @Test
    void fatalFailureRemainsPrimaryWhenRequestFailureArrivesLater() {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        LineSessionState.Request request = state.beginRequest();
        AssertionError fatal = new AssertionError("fatal output");
        LineSessionException responseLimit =
                state.failure(LineSessionException.Reason.RESPONSE_TOO_LARGE, "response limit", null);

        state.recordFatalError(fatal);
        LineSessionException selected = state.recordRequestFailure(request, () -> responseLimit);

        assertSame(responseLimit, selected);
        assertSame(fatal, ((LineSessionState.FatalSnapshot) state.terminal()).error());
        assertTrue(discarded.isEmpty());
    }

    @Test
    void laterRequestFailureIsDiscardedWithoutMutatingTheSelectedRequestFailure() {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        LineSessionState.Request request = state.beginRequest();
        LineSessionException first = state.failure("first", null);
        LineSessionException second = state.failure("second", null);

        assertSame(first, state.recordRequestFailure(request, () -> first));
        assertSame(first, state.recordRequestFailure(request, () -> second));

        assertTrue(discarded.isEmpty());
    }

    @Test
    void lateFatalFailureDoesNotReplaceSelectedRequestFailure() {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        LineSessionState.Request request = state.beginRequest();
        LineSessionException responseLimit =
                state.failure(LineSessionException.Reason.RESPONSE_TOO_LARGE, "response limit", null);
        AssertionError fatal = new AssertionError("fatal output");

        state.recordRequestFailure(request, () -> responseLimit);
        LineSessionState.TerminalSnapshot selected = state.recordFatalError(fatal);

        assertSame(responseLimit, selected.primary());
        assertSame(
                responseLimit,
                assertInstanceOf(LineSessionState.FailureSnapshot.class, selected)
                        .primary());
        assertEquals(List.of(fatal), discarded);
        assertSame(responseLimit, request.failure());
    }

    @Test
    void terminalTranscriptSnapshotCannotBlockCloseWhileHoldingTheStateMonitor() throws Exception {
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        LineSessionState state = new LineSessionState(() -> {
            snapshotEntered.countDown();
            awaitUninterruptibly(releaseSnapshot);
            return new LineTranscript("diagnostic", false, false);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LineSessionState.TerminalSnapshot> terminal = executor.submit(() -> state.recordTerminalFailure(
                    LineSessionException.Reason.FAILURE, "failure", new IllegalStateException("failure")));
            assertTrue(snapshotEntered.await(1, TimeUnit.SECONDS));

            Future<Boolean> close = executor.submit(state::claimClose);
            assertTrue(close.get(1, TimeUnit.SECONDS));

            releaseSnapshot.countDown();
            assertTrue(terminal.get(1, TimeUnit.SECONDS) instanceof LineSessionState.FailureSnapshot);
        } finally {
            releaseSnapshot.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
