/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ExpectException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ExpectSessionStateTest {

    @Test
    void closeOwnsTerminalBeforeLosingRegexErrorAndReportsErrorOutsideStateLock() {
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> reportedThread = new AtomicReference<>();
        AtomicReference<Error> reportedError = new AtomicReference<>();
        AtomicBoolean reporterHeldStateLock = new AtomicBoolean();
        AtomicReference<ExpectSessionState> stateReference = new AtomicReference<>();
        ExpectSessionState state = new ExpectSessionState(256, 256, (thread, error) -> {
            reports.incrementAndGet();
            reportedThread.set(thread);
            reportedError.set(error);
            reporterHeldStateLock.set(Thread.holdsLock(stateReference.get()));
        });
        stateReference.set(state);
        Thread evaluatorThread = new Thread();
        AssertionError evaluatorError = new AssertionError("late evaluator failure");

        assertTrue(state.close());
        RuntimeException selected = state.arbitrateRegexFailure("not found", evaluatorError, evaluatorThread);

        ExpectException closed = (ExpectException) selected;
        assertEquals(ExpectException.Reason.CLOSED, closed.reason());
        assertEquals(1, reports.get());
        assertSame(evaluatorThread, reportedThread.get());
        assertSame(evaluatorError, reportedError.get());
        assertFalse(reporterHeldStateLock.get());
    }

    @Test
    void outputFailureOwnsTerminalWithoutMutatingItsCause() {
        AtomicInteger reports = new AtomicInteger();
        ExpectSessionState state = state((thread, error) -> reports.incrementAndGet());
        IllegalStateException outputFailure = new IllegalStateException("output failed first");
        AssertionError evaluatorError = new AssertionError("late evaluator failure");

        ExpectSessionState.OutputFailureDecision decision = state.recordOutputFailure(outputFailure);
        RuntimeException selected = state.arbitrateRegexFailure("not found", evaluatorError, new Thread());

        ExpectException failure = (ExpectException) selected;
        assertTrue(decision.first());
        assertEquals(ExpectException.Reason.FAILURE, failure.reason());
        assertSame(outputFailure, failure.getCause());
        assertEquals(0, outputFailure.getSuppressed().length);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(evaluatorError, failure.getSuppressed()[0]);
        assertEquals(0, reports.get());
    }

    @Test
    void laterCloseDoesNotReplaceEofTerminal() {
        ExpectSessionState state = state((thread, error) -> {});

        state.recordStdoutEof();
        assertTrue(state.close());
        ExpectException terminal = org.junit.jupiter.api.Assertions.assertThrows(
                ExpectException.class, () -> state.throwIfTerminal("not found"));
        assertEquals(ExpectException.Reason.EOF, terminal.reason());
    }

    @Test
    void laterFatalOutputFailureIsReportedInsteadOfDecoratingThePrimary() {
        ExpectSessionState state = state((thread, error) -> {});
        IllegalStateException primary = new IllegalStateException("primary");
        AssertionError secondary = new AssertionError("secondary");
        state.recordOutputFailure(primary);

        ExpectSessionState.OutputFailureDecision decision = state.recordOutputFailure(secondary);

        assertFalse(decision.first());
        assertSame(secondary, decision.fatalToPublish());
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void repeatedCanonicalErrorIsNotPublishedAsALateFailure() {
        ExpectSessionState state = state((thread, error) -> {});
        AssertionError canonical = new AssertionError("canonical");

        state.recordOutputFailure(canonical);
        ExpectSessionState.OutputFailureDecision repeated = state.recordOutputFailure(canonical);

        assertFalse(repeated.first());
        assertNull(repeated.fatalToPublish());
    }

    private static ExpectSessionState state(java.util.function.BiConsumer<Thread, Error> reporter) {
        return new ExpectSessionState(256, 256, reporter);
    }
}
