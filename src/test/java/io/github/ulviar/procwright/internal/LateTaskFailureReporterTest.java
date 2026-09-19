/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class LateTaskFailureReporterTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportsTheFirstFailureOnceToTheCapturedTargetInEitherArrivalOrder(boolean failureFirst) throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 2);
        LateTaskFailureReporter lateFailure = new LateTaskFailureReporter(reporter);
        AtomicInteger reports = new AtomicInteger();
        AtomicInteger replacementReports = new AtomicInteger();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        AtomicReference<Thread> observedSource = new AtomicReference<>();
        Thread source = Thread.ofPlatform().name("late-task-source").unstarted(() -> {});
        source.setUncaughtExceptionHandler((thread, failure) -> {
            observedSource.set(thread);
            observedFailure.set(failure);
            reports.incrementAndGet();
        });
        lateFailure.bind(source);
        source.setUncaughtExceptionHandler((thread, failure) -> replacementReports.incrementAndGet());
        AssertionError expected = new AssertionError("task failed");

        if (failureFirst) {
            lateFailure.record(expected);
        } else {
            lateFailure.abandon();
        }
        assertTrue(reporter.awaitSettlement(Duration.ofSeconds(1)));
        assertEquals(0, reports.get(), "reporting requires both abandonment and a failure");

        if (failureFirst) {
            lateFailure.abandon();
        } else {
            lateFailure.record(expected);
        }
        lateFailure.abandon();
        lateFailure.record(new AssertionError("repeated failure"));
        assertTrue(reporter.awaitSettlement(Duration.ofSeconds(1)));

        assertEquals(1, reports.get());
        assertSame(expected, observedFailure.get());
        assertEquals(source.getName(), observedSource.get().getName());
        assertNotSame(source, observedSource.get());
        assertEquals(0, replacementReports.get());
    }
}
