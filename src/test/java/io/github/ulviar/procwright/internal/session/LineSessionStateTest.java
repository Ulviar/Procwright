/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    void activeRequestOwnsExactOnceFailureAttributionSettlement() {
        AtomicInteger seals = new AtomicInteger();
        LineSessionState state =
                new LineSessionState(() -> new LineTranscript("", false, false), ignored -> {}, seals::incrementAndGet);
        LineSessionState.Request request = state.beginRequest();

        state.recordStdoutEof();
        assertEquals(0, seals.get());
        state.completeRequest(request);
        request.close();
        request.close();

        assertEquals(1, seals.get());
    }

    @Test
    void retrySafeReleaseSettlesIdleEofBeforeTheRequestScopeCloses() {
        AtomicInteger seals = new AtomicInteger();
        LineSessionState state =
                new LineSessionState(() -> new LineTranscript("", false, false), ignored -> {}, seals::incrementAndGet);
        LineSessionState.Request request = state.beginRequest();
        LineSessionException retryable = state.timeout();

        state.recordStdoutEof();
        assertSame(retryable, state.releaseRetryablePreWrite(request, retryable));
        assertEquals(1, seals.get());

        request.close();
        assertEquals(1, seals.get());
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
        assertEquals(List.of(responseLimit), discarded);
        assertEquals(0, fatal.getSuppressed().length);
        assertEquals(0, responseLimit.getSuppressed().length);
    }

    @Test
    void laterRequestFailureIsReportedWithoutMutatingTheSelectedRequestFailure() {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        LineSessionState.Request request = state.beginRequest();
        LineSessionException first = state.failure("first", null);
        LineSessionException second = state.failure("second", null);

        assertSame(first, state.recordRequestFailure(request, () -> first));
        assertSame(first, state.recordRequestFailure(request, () -> second));

        assertEquals(List.of(second), discarded);
        assertEquals(0, first.getSuppressed().length);
        assertEquals(0, second.getSuppressed().length);
    }

    @Test
    void fatalFailureReplacesARecoverableRequestFailure() {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        LineSessionState.Request request = state.beginRequest();
        LineSessionException responseLimit =
                state.failure(LineSessionException.Reason.RESPONSE_TOO_LARGE, "response limit", null);
        AssertionError fatal = new AssertionError("fatal output");

        state.recordRequestFailure(request, () -> responseLimit);
        LineSessionState.TerminalSnapshot selected = state.recordFatalError(fatal);

        assertSame(fatal, selected.primary());
        assertSame(fatal, ((LineSessionState.FatalSnapshot) selected).error());
        assertEquals(List.of(responseLimit), discarded);
        assertSame(responseLimit, request.failure());
        assertEquals(0, fatal.getSuppressed().length);
        assertEquals(0, responseLimit.getSuppressed().length);
    }

    @Test
    void losingTerminalFailureDoesNotHoldTheStateMonitorOrMutateTheWinner() throws Exception {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "primary", primary);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<LineSessionState.TerminalSnapshot> selection = null;
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            selection = executor.submit(
                    () -> state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "secondary", secondary));
            selection.get(1, TimeUnit.SECONDS);
            assertEquals(List.of(secondary), discarded);

            Future<LineSessionState.TerminalSnapshot> observation = executor.submit(state::terminal);
            assertSame(primary, observation.get(1, TimeUnit.SECONDS).primary());
        } finally {
            if (selection != null) {
                selection.get(1, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, secondary.getSuppressed().length);
    }

    @Test
    void fatalSelectionDoesNotWaitForOrMutateEarlierFailures() throws Exception {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        AssertionError fatal = new AssertionError("fatal");
        state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "primary", primary);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> losingSelection = null;
        Future<LineSessionState.TerminalSnapshot> fatalSelection = null;
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            losingSelection = executor.submit(() -> {
                state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "secondary", secondary);
            });
            losingSelection.get(1, TimeUnit.SECONDS);

            fatalSelection = executor.submit(() -> state.recordFatalError(fatal));
            assertSame(fatal, fatalSelection.get(1, TimeUnit.SECONDS).primary());
            assertEquals(List.of(secondary, primary), discarded);
            assertSame(fatal, state.terminal().primary());
        } finally {
            if (losingSelection != null) {
                losingSelection.get(1, TimeUnit.SECONDS);
            }
            if (fatalSelection != null) {
                assertSame(fatal, fatalSelection.get(1, TimeUnit.SECONDS).primary());
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, secondary.getSuppressed().length);
        assertEquals(0, fatal.getSuppressed().length);
    }

    @Test
    void completeRequestDoesNotWaitForOrMutateALosingTerminalFailure() throws Exception {
        List<Throwable> discarded = new ArrayList<>();
        LineSessionState state = new LineSessionState(() -> new LineTranscript("", false, false), discarded::add);
        LineSessionState.Request request = state.beginRequest();
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "primary", primary);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> losingSelection = null;
        Future<LineSessionException> completion = null;
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            losingSelection = executor.submit(() -> {
                state.recordTerminalFailure(LineSessionException.Reason.FAILURE, "secondary", secondary);
            });
            losingSelection.get(1, TimeUnit.SECONDS);
            assertEquals(List.of(secondary), discarded);

            completion = executor.submit(() -> {
                return assertThrows(LineSessionException.class, () -> state.completeRequest(request));
            });
            assertSame(primary, completion.get(1, TimeUnit.SECONDS).getCause());
            assertSame(primary, state.terminal().primary());
        } finally {
            if (losingSelection != null) {
                losingSelection.get(1, TimeUnit.SECONDS);
            }
            if (completion != null) {
                LineSessionException failure = completion.get(1, TimeUnit.SECONDS);
                assertSame(primary, failure.getCause());
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, secondary.getSuppressed().length);
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
