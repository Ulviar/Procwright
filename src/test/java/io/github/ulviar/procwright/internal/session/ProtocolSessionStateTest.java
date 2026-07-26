/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void successfulCompletionReleasesTheActiveRequest() {
        ProtocolSessionState state = state();
        ProtocolSessionState.RequestOutcome completed = state.beginRequest();

        state.completeRequest(completed);

        ProtocolSessionState.RequestOutcome next = state.beginRequest();
        next.close();
    }

    @Test
    void terminalFailureSelectedBeforeCloseRemainsCanonical() {
        ProtocolSessionState state = state();
        IllegalStateException cause = new IllegalStateException("overflow");

        state.recordTerminalFailure(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "overflow", cause);
        ProtocolSessionState.CloseClaim close = state.claimClose(true);

        assertTrue(close.owner());
        ProtocolSessionState.FailureSnapshot terminal =
                assertInstanceOf(ProtocolSessionState.FailureSnapshot.class, close.terminalToPublish());
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
        assertTrue(discarded.isEmpty());
        ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, state::ensureOpen);
        assertEquals(ProtocolSessionException.Reason.CLOSED, followUp.reason());
    }

    @Test
    void outputFailuresAfterCloseAreOnlyReported() {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        IllegalStateException runtime = new IllegalStateException("late output");
        AssertionError fatal = new AssertionError("late fatal output");

        state.claimClose(true);
        ProtocolSessionState.OutputSelection runtimeSelection =
                state.recordOutputFailure(ProtocolSessionException.Reason.FAILURE, "late", runtime);
        ProtocolSessionState.OutputSelection fatalSelection = state.recordOutputFatalError(fatal);

        assertTrue(runtimeSelection.rejectedAfterClose());
        assertTrue(fatalSelection.rejectedAfterClose());
        assertInstanceOf(ProtocolSessionState.ClosedSnapshot.class, runtimeSelection.selected());
        assertInstanceOf(ProtocolSessionState.ClosedSnapshot.class, fatalSelection.selected());
        assertInstanceOf(ProtocolSessionState.ClosedSnapshot.class, state.terminal());
        assertEquals(List.of(fatal), discarded);
    }

    @Test
    void closeClaimsEncodeOwnershipAndPublication() {
        ProtocolSessionState silent = state();

        ProtocolSessionState.CloseClaim silentOwner = silent.claimClose(false);
        assertTrue(silentOwner.owner());
        assertNull(silentOwner.terminalToPublish());
        ProtocolSessionState.CloseClaim silentObserver = silent.claimClose(true);
        assertFalse(silentObserver.owner());
        assertNull(silentObserver.terminalToPublish());

        ProtocolSessionState publishing = state();
        ProtocolSessionState.CloseClaim publication = publishing.claimClose(true);
        assertTrue(publication.owner());
        assertInstanceOf(ProtocolSessionState.ClosedSnapshot.class, publication.terminalToPublish());
    }

    @Test
    void concurrentCloseClaimsHaveExactlyOneLifecycleOwner() throws Exception {
        int callers = 8;
        AtomicInteger transcriptSnapshots = new AtomicInteger();
        ProtocolSessionState state = new ProtocolSessionState(
                () -> {
                    transcriptSnapshots.incrementAndGet();
                    return new ProtocolTranscript("diagnostic", false, false);
                },
                OptionalInt::empty);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ProtocolSessionState.CloseClaim>> decisions = new ArrayList<>();
        try {
            for (int index = 0; index < callers; index++) {
                decisions.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return state.claimClose(true);
                }));
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            start.countDown();

            int owners = 0;
            int observers = 0;
            for (Future<ProtocolSessionState.CloseClaim> decision : decisions) {
                ProtocolSessionState.CloseClaim selected = decision.get(1, TimeUnit.SECONDS);
                if (selected.owner()) {
                    owners++;
                    assertNotNull(selected.terminalToPublish());
                } else {
                    observers++;
                    assertNull(selected.terminalToPublish());
                }
            }

            assertEquals(1, owners);
            assertEquals(callers - 1, observers);
            assertEquals(1, transcriptSnapshots.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void lateFatalFailureDoesNotReplaceClosedOutcome() {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        ProtocolSessionException closed = state.recordRequestFailure(request, () -> state.closed(null));
        AssertionError fatal = new AssertionError("fatal");

        state.claimClose(true);
        ProtocolSessionState.TerminalSnapshot selected = state.recordFatalError(fatal);

        ProtocolSessionException selectedClosed = assertInstanceOf(ProtocolSessionState.ClosedSnapshot.class, selected)
                .failure();
        assertEquals(ProtocolSessionException.Reason.CLOSED, selectedClosed.reason());
        assertSame(closed, request.failure());
        assertEquals(List.of(fatal), discarded);
        ProtocolSessionException observed = assertThrows(ProtocolSessionException.class, state::ensureOpen);
        assertEquals(ProtocolSessionException.Reason.CLOSED, observed.reason());
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
    void fatalFailureSelectedBeforeTimeoutRemainsTerminal() {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        AssertionError fatal = new AssertionError("fatal");

        state.recordFatalError(fatal);
        ProtocolSessionException timeout = state.recordRequestTimeout(request);

        assertSame(
                fatal,
                assertInstanceOf(ProtocolSessionState.FatalSnapshot.class, state.terminal())
                        .error());
        assertNull(request.failure());
        assertTrue(discarded.isEmpty());
    }

    @Test
    void timeoutSelectedBeforeFatalFailureRemainsTerminal() {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        AssertionError fatal = new AssertionError("fatal");

        ProtocolSessionException timeout = state.recordRequestTimeout(request);
        state.recordFatalError(fatal);

        ProtocolSessionState.FailureSnapshot selected =
                assertInstanceOf(ProtocolSessionState.FailureSnapshot.class, state.terminal());
        assertSame(timeout, selected.primary());
        assertSame(timeout, request.failure());
        assertEquals(List.of(fatal), discarded);
    }

    @Test
    void laterRequestFailureIsDiscardedWithoutMutatingTheSelectedRequestFailure() {
        List<Throwable> discarded = new ArrayList<>();
        ProtocolSessionState state = state(discarded);
        ProtocolSessionState.RequestOutcome request = state.beginRequest();
        ProtocolSessionException first = state.failure(ProtocolSessionException.Reason.FAILURE, "first", null);
        ProtocolSessionException second =
                state.failure(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, "second", null);

        assertSame(first, state.recordRequestFailure(request, () -> first));
        assertSame(first, state.recordRequestFailure(request, () -> second));

        assertTrue(discarded.isEmpty());
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
    void staleRequestCannotCompleteOrEndTheActiveRequest() {
        ProtocolSessionState state =
                new ProtocolSessionState(() -> new ProtocolTranscript("diagnostic", false, false), OptionalInt::empty);
        ProtocolSessionState.RequestOutcome stale = state.beginRequest();
        stale.close();
        ProtocolSessionState.RequestOutcome active = state.beginRequest();

        assertThrows(IllegalStateException.class, () -> state.completeRequest(stale));
        stale.close();
        assertThrows(IllegalStateException.class, state::beginRequest);
        state.completeRequest(active);
        active.close();
        state.beginRequest().close();
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

            Future<ProtocolSessionState.CloseClaim> close = executor.submit(() -> state.claimClose(false));
            ProtocolSessionState.CloseClaim selected = close.get(1, TimeUnit.SECONDS);
            assertTrue(selected.owner());
            assertNull(selected.terminalToPublish());

            releaseSnapshot.countDown();
            assertInstanceOf(ProtocolSessionState.FailureSnapshot.class, terminal.get(1, TimeUnit.SECONDS));
        } finally {
            releaseSnapshot.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
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
