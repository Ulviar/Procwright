/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport.failOnceOn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SessionTerminalTest {

    @Test
    void rawModePublishesTheFirstProcessClaim() throws Exception {
        SessionTerminal terminal = terminal(SessionOutputMode.RAW);
        SessionTerminal.ProcessClaim claim = terminal.claimClose(false);
        SessionExit expected = new SessionExit(OptionalInt.of(17), false);

        assertTrue(terminal.completeNaturalExit(new SessionExit(OptionalInt.of(3), false)));
        assertNull(terminal.claimFailure(new AssertionError("late")));
        claim.succeed(expected);

        assertSame(expected, terminal.publicExit().get(1, TimeUnit.SECONDS));
        assertTrue(terminal.processPublished());
        assertTrue(terminal.publicExitCompleted());
    }

    @Test
    void primaryClaimSelectionIsMonotonicAndDistinctFromNaturalExit() {
        SessionTerminal claimed = terminal(SessionOutputMode.RAW);
        assertFalse(claimed.primaryClaimSelected());

        SessionTerminal.ProcessClaim claim = claimed.claimClose(false);
        assertTrue(claimed.primaryClaimSelected());
        claim.succeed(new SessionExit(OptionalInt.of(0), false));
        assertTrue(claimed.primaryClaimSelected());

        SessionTerminal natural = terminal(SessionOutputMode.RAW);
        natural.completeNaturalExit(new SessionExit(OptionalInt.of(0), false));
        assertFalse(natural.primaryClaimSelected());
    }

    @Test
    void helperModePublishesOnlyAfterBothFactsAreKnownInEitherOrder() throws Exception {
        SessionExit expected = new SessionExit(OptionalInt.of(3), false);
        SessionTerminal processFirst = terminal(SessionOutputMode.LINE);
        assertTrue(processFirst.completeNaturalExit(expected));
        assertFalse(processFirst.publicExit().isDone());
        processFirst.settleMode(SessionTerminal.ModeSettlement.completed(null));
        assertSame(expected, processFirst.publicExit().get(1, TimeUnit.SECONDS));

        SessionTerminal modeFirst = terminal(SessionOutputMode.PROTOCOL);
        modeFirst.settleMode(SessionTerminal.ModeSettlement.completed(null));
        assertFalse(modeFirst.publicExit().isDone());
        assertTrue(modeFirst.completeNaturalExit(expected));
        assertSame(expected, modeFirst.publicExit().get(1, TimeUnit.SECONDS));
    }

    @Test
    void processObserverDoesNotWaitForHelperModeSettlement() throws Exception {
        SessionTerminal terminal = terminal(SessionOutputMode.LINE);
        CompletableFuture<SessionExit> observed = new CompletableFuture<>();
        terminal.observeProcess((result, failure) -> {
            if (failure == null) {
                observed.complete(result);
            } else {
                observed.completeExceptionally(failure);
            }
        });
        SessionExit expected = new SessionExit(OptionalInt.of(0), false);

        assertTrue(terminal.completeNaturalExit(expected));

        assertSame(expected, observed.get(1, TimeUnit.SECONDS));
        assertFalse(terminal.publicExit().isDone());
    }

    @Test
    void naturalProcessSettlementRemainsFallbackUntilPublicPublication() {
        SessionTerminal terminal = terminal(SessionOutputMode.LINE);
        CompletableFuture<SessionExit> observedProcess = new CompletableFuture<>();
        terminal.observeProcess((result, failure) -> {
            if (failure == null) {
                observedProcess.complete(result);
            } else {
                observedProcess.completeExceptionally(failure);
            }
        });
        SessionExit naturalExit = new SessionExit(OptionalInt.of(0), false);
        IllegalStateException helperFailure = new IllegalStateException("helper");

        assertTrue(terminal.completeNaturalExit(naturalExit));
        assertSame(naturalExit, observedProcess.join());
        SessionTerminal.ProcessClaim failureClaim = terminal.claimFailure(helperFailure);
        failureClaim.fail();
        terminal.settleMode(SessionTerminal.ModeSettlement.completed(null));

        assertSame(helperFailure, completionFailure(terminal));
    }

    @Test
    void modeFailureReplacesProcessSuccessButNeverProcessFailure() {
        IllegalStateException modeFailure = new IllegalStateException("mode");
        IllegalStateException processFailure = new IllegalStateException("process");

        SessionTerminal success = terminal(SessionOutputMode.LINE);
        assertTrue(success.completeNaturalExit(new SessionExit(OptionalInt.of(0), false)));
        success.settleMode(SessionTerminal.ModeSettlement.completed(modeFailure));
        assertSame(modeFailure, completionFailure(success));

        SessionTerminal failed = terminal(SessionOutputMode.LINE);
        failed.claimFailure(processFailure).fail();
        failed.settleMode(SessionTerminal.ModeSettlement.completed(modeFailure));
        assertSame(processFailure, completionFailure(failed));
    }

    @Test
    void eofObservedByModeDoesNotReplaceSuccessfulProcessExit() throws Exception {
        SessionTerminal terminal = terminal(SessionOutputMode.EXPECT);
        SessionExit expected = new SessionExit(OptionalInt.of(0), false);

        terminal.settleMode(SessionTerminal.ModeSettlement.processSuccessPreferred(new IllegalStateException("eof")));
        assertTrue(terminal.completeNaturalExit(expected));

        assertSame(expected, terminal.publicExit().get(1, TimeUnit.SECONDS));
    }

    @Test
    void failureClaimPreservesPrimaryIdentityAndReportsCleanupSeparately() {
        SessionTerminal terminal = terminal(SessionOutputMode.RAW);
        AssertionError primary = new AssertionError("primary");
        IllegalStateException cleanup = new IllegalStateException("cleanup");
        SessionTerminal.ProcessClaim claim = terminal.claimFailure(primary);

        claim.addFailure(cleanup);
        claim.fail();

        assertSame(primary, completionFailure(terminal));
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, cleanup.getSuppressed().length);
    }

    @Test
    void cancellingOnePublicViewDoesNotCancelTerminalPublication() throws Exception {
        SessionTerminal terminal = terminal(SessionOutputMode.RAW);
        CompletableFuture<SessionExit> cancelled = terminal.publicExit();
        CompletableFuture<SessionExit> observed = terminal.publicExit();
        SessionExit expected = new SessionExit(OptionalInt.of(0), false);

        assertTrue(cancelled.cancel(false));
        assertTrue(terminal.completeNaturalExit(expected));

        assertTrue(cancelled.isCancelled());
        assertSame(expected, observed.get(1, TimeUnit.SECONDS));
    }

    @Test
    void diagnosticFailureDoesNotReplaceSuccessfulExit() throws Exception {
        AssertionError diagnosticFailure = new AssertionError("diagnostic");
        DiagnosticEmitter diagnostics = failOnceOn(
                DiagnosticsSettings.disabled().withListener(event -> {}),
                "session-terminal-test",
                DiagnosticEventType.PROCESS_EXITED,
                diagnosticFailure);
        SessionTerminal terminal = new SessionTerminal(SessionOutputMode.RAW, diagnostics);
        SessionExit expected = new SessionExit(OptionalInt.of(0), false);

        assertTrue(terminal.completeNaturalExit(expected));

        assertSame(expected, terminal.publicExit().get(1, TimeUnit.SECONDS));
    }

    @Test
    void failureDiagnosticDoesNotReplaceThePrimaryFailure() {
        AssertionError diagnosticFailure = new AssertionError("diagnostic");
        DiagnosticEmitter diagnostics = failOnceOn(
                DiagnosticsSettings.disabled().withListener(event -> {}),
                "session-terminal-test",
                DiagnosticEventType.PROCESS_FAILED,
                diagnosticFailure);
        SessionTerminal terminal = new SessionTerminal(SessionOutputMode.RAW, diagnostics);
        IllegalStateException primary = new IllegalStateException("primary");

        terminal.claimFailure(primary).fail();

        assertSame(primary, completionFailure(terminal));
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void closeAndFailureRaceProduceOnePrimaryOwner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int iteration = 0; iteration < 200; iteration++) {
                SessionTerminal terminal = terminal(SessionOutputMode.LINE);
                AssertionError failure = new AssertionError("failure " + iteration);
                CountDownLatch start = new CountDownLatch(1);
                Future<SessionTerminal.ProcessClaim> close =
                        executor.submit(() -> after(start, () -> terminal.claimClose(false)));
                Future<SessionTerminal.ProcessClaim> failed =
                        executor.submit(() -> after(start, () -> terminal.claimFailure(failure)));
                Future<Boolean> natural = executor.submit(() ->
                        after(start, () -> terminal.completeNaturalExit(new SessionExit(OptionalInt.of(0), false))));

                start.countDown();
                SessionTerminal.ProcessClaim closeClaim = close.get(1, TimeUnit.SECONDS);
                SessionTerminal.ProcessClaim failureClaim = failed.get(1, TimeUnit.SECONDS);
                assertEquals(1, count(closeClaim) + count(failureClaim));
                assertTrue(natural.get(1, TimeUnit.SECONDS));

                if (failureClaim != null) {
                    failureClaim.fail();
                    terminal.settleMode(SessionTerminal.ModeSettlement.completed(null));
                    assertSame(failure, completionFailure(terminal));
                } else {
                    SessionExit expected = new SessionExit(OptionalInt.of(0), false);
                    closeClaim.succeed(expected);
                    terminal.settleMode(SessionTerminal.ModeSettlement.completed(null));
                    assertSame(expected, terminal.publicExit().get(1, TimeUnit.SECONDS));
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static SessionTerminal terminal(SessionOutputMode mode) {
        return new SessionTerminal(
                mode,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-terminal-test", CommandEcho.empty()));
    }

    private static Throwable completionFailure(SessionTerminal terminal) {
        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> terminal.publicExit().get(1, TimeUnit.SECONDS));
        return observed.getCause();
    }

    private static <T> T after(CountDownLatch start, java.util.function.Supplier<T> action) {
        try {
            start.await();
            return action.get();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
        }
    }

    private static int count(Object value) {
        return value == null ? 0 : 1;
    }
}
