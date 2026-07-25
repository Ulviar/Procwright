/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolSessionStateTest {

    @Test
    void terminalFailureSelectedBeforeCloseRemainsCanonical() {
        ProtocolSessionState state = state();
        IllegalStateException cause = new IllegalStateException("overflow");

        state.recordTerminalFailure(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "overflow", cause);
        ProtocolSessionState.CloseDecision close = state.claimClose(true);

        ProtocolSessionState.PublishTerminal publication =
                assertInstanceOf(ProtocolSessionState.PublishTerminal.class, close);
        ProtocolSessionState.FailureSnapshot terminal =
                assertInstanceOf(ProtocolSessionState.FailureSnapshot.class, publication.terminal());
        assertSame(cause, terminal.primary());
        ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, state::ensureOpen);
        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, followUp.reason());
        assertSame(cause, followUp.getCause());
    }

    @Test
    void terminalFailureIncludesProcessExitObservedAfterFailureSelection() {
        AtomicReference<OptionalInt> exitCode = new AtomicReference<>(OptionalInt.empty());
        ProtocolSessionState state =
                new ProtocolSessionState(() -> new ProtocolTranscript("diagnostic", false, false), exitCode::get);
        state.recordTerminalFailure(
                ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW,
                "overflow",
                new IllegalStateException("overflow"));

        ProtocolSessionException beforeExit = assertThrows(ProtocolSessionException.class, state::ensureOpen);
        assertTrue(beforeExit.exitCode().isEmpty());

        exitCode.set(OptionalInt.of(17));

        ProtocolSessionException afterExit = assertThrows(ProtocolSessionException.class, state::ensureOpen);
        assertEquals(17, afterExit.exitCode().orElseThrow());
    }

    @Test
    void exitCodeSupplierIsNeverCalledUnderStateMonitor() {
        AtomicReference<ProtocolSessionState> owner = new AtomicReference<>();
        AtomicInteger snapshots = new AtomicInteger();
        ProtocolSessionState state =
                new ProtocolSessionState(() -> new ProtocolTranscript("diagnostic", false, false), () -> {
                    assertFalse(Thread.holdsLock(owner.get()), "exit code supplier ran under the state monitor");
                    snapshots.incrementAndGet();
                    return OptionalInt.empty();
                });
        owner.set(state);
        ProtocolSessionState.RequestOutcome request = state.beginRequest();

        state.recordTerminalFailure(
                ProtocolSessionException.Reason.FAILURE, "failure", new IllegalStateException("failure"));
        assertThrows(ProtocolSessionException.class, () -> state.completeRequest(request));
        assertThrows(ProtocolSessionException.class, state::ensureOpen);
        request.close();

        ProtocolSessionState.RequestOutcome followUp = state.beginRequest();
        ProtocolSessionException selected = state.recordRequestFailure(followUp, () -> state.closed(null));
        assertEquals(ProtocolSessionException.Reason.FAILURE, selected.reason());
        followUp.close();

        assertEquals(2, snapshots.get());
    }

    @Test
    void closeSelectedBeforeNonfatalFailureRemainsCanonical() {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        IllegalStateException late = new IllegalStateException("late");

        state.claimClose(true);
        ProtocolSessionState.TerminalSnapshot selected =
                state.recordTerminalFailure(ProtocolSessionException.Reason.FAILURE, "late", late);

        assertInstanceOf(ProtocolSessionState.ClosedSnapshot.class, selected);
        assertEquals(List.of(late), discarded);
        ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, state::ensureOpen);
        assertEquals(ProtocolSessionException.Reason.CLOSED, followUp.reason());
        assertEquals(0, followUp.getSuppressed().length);
    }

    @Test
    void closeDecisionVariantsEncodeOwnershipAndPublication() {
        ProtocolSessionState silent = state();

        assertInstanceOf(ProtocolSessionState.CloseSilently.class, silent.claimClose(false));
        assertInstanceOf(ProtocolSessionState.AlreadyClosed.class, silent.claimClose(true));

        ProtocolSessionState publishing = state();
        ProtocolSessionState.PublishTerminal publication =
                assertInstanceOf(ProtocolSessionState.PublishTerminal.class, publishing.claimClose(true));
        assertInstanceOf(ProtocolSessionState.ClosedSnapshot.class, publication.terminal());
    }

    @Test
    void fatalFailurePromotesClosedWithoutMutatingTheActiveClosedFailure() {
        ProtocolSessionState state = state();
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        ProtocolSessionException closed = state.recordRequestFailure(request, () -> state.closed(null));
        AssertionError fatal = new AssertionError("fatal");

        state.claimClose(true);
        ProtocolSessionState.TerminalSnapshot selected = state.recordFatalError(fatal);

        assertSame(
                fatal,
                assertInstanceOf(ProtocolSessionState.FatalSnapshot.class, selected)
                        .error());
        assertSame(closed, request.failure());
        assertEquals(0, fatal.getSuppressed().length);
        assertEquals(0, closed.getSuppressed().length);
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
            List<Throwable> discarded = new ArrayList<>();
            ProtocolSessionState state = state(discarded);
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
            assertSame(
                    fatal,
                    assertInstanceOf(ProtocolSessionState.FatalSnapshot.class, selected)
                            .error());
            if (fatalFirst) {
                assertNull(request.failure());
            } else {
                assertSame(timeout, request.failure());
            }
            assertEquals(List.of(timeout), discarded);
            assertEquals(0, fatal.getSuppressed().length);
            assertEquals(0, timeout.getSuppressed().length);
        }
    }

    @Test
    void laterRequestFailureIsReportedWithoutMutatingTheSelectedRequestFailure() {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        ProtocolSessionException first = state.failure(ProtocolSessionException.Reason.FAILURE, "first", null);
        ProtocolSessionException second =
                state.failure(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, "second", null);

        assertSame(first, state.recordRequestFailure(request, () -> first));
        assertSame(first, state.recordRequestFailure(request, () -> second));

        assertEquals(List.of(second), discarded);
        assertEquals(0, first.getSuppressed().length);
        assertEquals(0, second.getSuppressed().length);
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
        AtomicInteger seals = new AtomicInteger();
        ProtocolSessionState state = new ProtocolSessionState(
                () -> new ProtocolTranscript("diagnostic", false, false),
                OptionalInt::empty,
                ignored -> {},
                seals::incrementAndGet);
        ProtocolSessionState.RequestOutcome request = state.beginRequest();

        state.recordStdoutEof();
        assertEquals(0, seals.get());
        state.completeRequest(request);
        request.close();
        request.close();

        assertEquals(1, seals.get());
    }

    @Test
    void staleRequestCannotCompleteOrEndTheActiveRequest() {
        AtomicInteger seals = new AtomicInteger();
        ProtocolSessionState state = new ProtocolSessionState(
                () -> new ProtocolTranscript("diagnostic", false, false),
                OptionalInt::empty,
                ignored -> {},
                seals::incrementAndGet);
        ProtocolSessionState.RequestOutcome stale = state.beginRequest();
        stale.close();
        ProtocolSessionState.RequestOutcome active = state.beginRequest();
        state.recordStdoutEof();

        assertThrows(IllegalStateException.class, () -> state.completeRequest(stale));
        stale.close();
        assertEquals(0, seals.get());
        state.completeRequest(active);
        active.close();
        assertEquals(1, seals.get());
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

    @Test
    void losingTerminalFailureDoesNotHoldTheStateMonitorOrMutateTheWinner() throws Exception {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        state.recordTerminalFailure(ProtocolSessionException.Reason.FAILURE, "primary", primary);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ProtocolSessionState.TerminalSnapshot> selection = null;
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            selection = executor.submit(
                    () -> state.recordTerminalFailure(ProtocolSessionException.Reason.FAILURE, "secondary", secondary));
            selection.get(1, TimeUnit.SECONDS);
            assertEquals(List.of(secondary), discarded);

            Future<ProtocolSessionState.TerminalSnapshot> observation = executor.submit(state::terminal);
            assertSame(
                    primary,
                    assertInstanceOf(ProtocolSessionState.FailureSnapshot.class, observation.get(1, TimeUnit.SECONDS))
                            .primary());
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
    void terminalTranscriptSnapshotCannotBlockCloseWhileHoldingTheStateMonitor() throws Exception {
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        ProtocolSessionState state = new ProtocolSessionState(
                () -> {
                    snapshotEntered.countDown();
                    awaitUninterruptibly(releaseSnapshot);
                    return new ProtocolTranscript("diagnostic", false, false);
                },
                OptionalInt::empty);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProtocolSessionState.TerminalSnapshot> terminal = executor.submit(() -> state.recordTerminalFailure(
                    ProtocolSessionException.Reason.FAILURE, "failure", new IllegalStateException("failure")));
            assertTrue(snapshotEntered.await(1, TimeUnit.SECONDS));

            Future<ProtocolSessionState.CloseDecision> close = executor.submit(() -> state.claimClose(false));
            assertInstanceOf(ProtocolSessionState.CloseSilently.class, close.get(1, TimeUnit.SECONDS));

            releaseSnapshot.countDown();
            assertInstanceOf(ProtocolSessionState.FailureSnapshot.class, terminal.get(1, TimeUnit.SECONDS));
        } finally {
            releaseSnapshot.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void completeRequestDoesNotWaitForOrMutateALosingTerminalFailure() throws Exception {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        state.recordTerminalFailure(ProtocolSessionException.Reason.FAILURE, "primary", primary);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> losingSelection = null;
        Future<ProtocolSessionException> completion = null;
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            losingSelection = executor.submit(() -> {
                state.recordTerminalFailure(ProtocolSessionException.Reason.FAILURE, "secondary", secondary);
            });
            losingSelection.get(1, TimeUnit.SECONDS);
            assertEquals(List.of(secondary), discarded);

            completion = executor.submit(() -> {
                return assertThrows(ProtocolSessionException.class, () -> state.completeRequest(request));
            });
            assertSame(primary, completion.get(1, TimeUnit.SECONDS).getCause());
            assertSame(
                    primary,
                    assertInstanceOf(ProtocolSessionState.FailureSnapshot.class, state.terminal())
                            .primary());
        } finally {
            if (losingSelection != null) {
                losingSelection.get(1, TimeUnit.SECONDS);
            }
            if (completion != null) {
                ProtocolSessionException failure = completion.get(1, TimeUnit.SECONDS);
                assertSame(primary, failure.getCause());
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, secondary.getSuppressed().length);
    }

    private static ProtocolSessionState state() {
        return new ProtocolSessionState(() -> new ProtocolTranscript("diagnostic", false, false), OptionalInt::empty);
    }

    private static ProtocolSessionState state(List<Throwable> discarded) {
        return new ProtocolSessionState(
                () -> new ProtocolTranscript("diagnostic", false, false), OptionalInt::empty, discarded::add);
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
