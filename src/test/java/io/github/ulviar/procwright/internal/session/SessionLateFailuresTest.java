/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class SessionLateFailuresTest {

    @Test
    void physicalFailureWaitsForTerminalArbitrationBeforeAttachment() {
        SessionLateFailures failures = new SessionLateFailures();
        AssertionError terminal = new AssertionError("stdin close failed");
        IllegalStateException physical = new IllegalStateException("stdout close failed");

        failures.record(physical, true);
        assertEquals(0, terminal.getSuppressed().length);

        failures.terminalCompleted(terminal);

        assertEquals(1, terminal.getSuppressed().length);
        assertSame(physical, terminal.getSuppressed()[0]);
    }

    @Test
    void failureRecordedAfterTerminalCompletionUsesTheSameAttachmentRule() {
        SessionLateFailures failures = new SessionLateFailures();
        AssertionError terminal = new AssertionError("terminal");
        IllegalStateException cleanup = new IllegalStateException("cleanup");
        failures.terminalCompleted(terminal);

        failures.record(cleanup, true);
        failures.record(cleanup, true);

        assertEquals(1, terminal.getSuppressed().length);
        assertSame(cleanup, terminal.getSuppressed()[0]);
    }

    @Test
    void terminalOutcomeCanBeCompletedOnlyOnce() {
        SessionLateFailures failures = new SessionLateFailures();
        failures.terminalCompleted(null);

        assertThrows(IllegalStateException.class, () -> failures.terminalCompleted(null));
    }

    @Test
    void deferredReportRetainsTheFailureSourceTarget() throws Exception {
        SessionLateFailures failures = new SessionLateFailures();
        AssertionError physical = new AssertionError("stdout close failed");
        AtomicReference<Throwable> sourceReport = new AtomicReference<>();
        AtomicReference<Throwable> terminalReport = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        Thread source = new Thread(() -> failures.record(physical, true), "physical-close-source");
        source.setUncaughtExceptionHandler((ignored, failure) -> {
            sourceReport.set(failure);
            reported.countDown();
        });
        Thread terminal = new Thread(() -> failures.terminalCompleted(null), "terminal-completion-source");
        terminal.setUncaughtExceptionHandler((ignored, failure) -> terminalReport.set(failure));

        source.start();
        source.join(TimeUnit.SECONDS.toMillis(1));
        terminal.start();
        terminal.join(TimeUnit.SECONDS.toMillis(1));

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
        assertSame(physical, sourceReport.get());
        assertNull(terminalReport.get());
    }
}
