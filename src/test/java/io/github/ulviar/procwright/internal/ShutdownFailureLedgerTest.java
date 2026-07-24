/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ShutdownFailureLedgerTest {

    @Test
    void firstFailureRetainsIdentityAndLaterFailuresKeepInsertionOrder() {
        ShutdownFailureLedger ledger = new ShutdownFailureLedger();
        AssertionError first = new AssertionError("first");
        IllegalStateException second = new IllegalStateException("second");
        AssertionError third = new AssertionError("third");

        ledger.record(first);
        ledger.record(second);
        ledger.record(third);

        AssertionError actual = assertThrows(AssertionError.class, ledger::rethrowIfPresent);
        assertSame(first, actual);
        assertEquals(List.of(second, third), List.of(actual.getSuppressed()));
    }

    @Test
    void interruptionBecomesPrimaryAndStatusIsClearedUntilCleanupFinishes() {
        ShutdownFailureLedger ledger = new ShutdownFailureLedger();
        IllegalStateException previous = new IllegalStateException("previous");
        ledger.record(previous);
        Thread.currentThread().interrupt();

        try {
            ledger.interruptionBoundary();

            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(ledger.wasInterrupted());
            CommandExecutionException actual = assertThrows(CommandExecutionException.class, ledger::rethrowIfPresent);
            assertTrue(actual.getCause() instanceof InterruptedException);
            assertEquals(List.of(previous), List.of(actual.getSuppressed()));

            ledger.restoreInterrupt();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void observedEventsApplyInterruptionPriorityWithoutNestingFailures() {
        ShutdownFailureLedger ledger = new ShutdownFailureLedger();
        IllegalStateException before = new IllegalStateException("before");
        InterruptedException interruption = new InterruptedException("interrupted");
        AssertionError after = new AssertionError("after");

        ledger.recordObserved(List.of(before, interruption, after));

        try {
            CommandExecutionException actual = assertThrows(CommandExecutionException.class, ledger::rethrowIfPresent);
            assertSame(interruption, actual.getCause());
            assertEquals(List.of(before, after), List.of(actual.getSuppressed()));
            assertEquals(0, before.getSuppressed().length);
            assertTrue(ledger.wasInterrupted());
        } finally {
            ledger.restoreInterrupt();
            Thread.interrupted();
        }
    }

    @Test
    void typedFailureContainingInterruptionRetainsIdentity() {
        ShutdownFailureLedger ledger = new ShutdownFailureLedger();
        CommandExecutionException expected =
                new CommandExecutionException("provider interrupted", new InterruptedException("stop"));

        ledger.record(expected);

        CommandExecutionException actual = assertThrows(CommandExecutionException.class, ledger::rethrowIfPresent);
        assertSame(expected, actual);
        assertTrue(ledger.wasInterrupted());
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            ledger.restoreInterrupt();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void attemptRecordsFatalIdentityAndStillObservesInterruptStatus() {
        ShutdownFailureLedger ledger = new ShutdownFailureLedger();
        AssertionError expected = new AssertionError("fatal");

        ledger.attempt(() -> {
            Thread.currentThread().interrupt();
            throw expected;
        });

        CommandExecutionException actual = assertThrows(CommandExecutionException.class, ledger::rethrowIfPresent);
        assertTrue(actual.getCause() instanceof InterruptedException);
        assertEquals(List.of(expected), List.of(actual.getSuppressed()));
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            ledger.restoreInterrupt();
        } finally {
            Thread.interrupted();
        }
    }
}
