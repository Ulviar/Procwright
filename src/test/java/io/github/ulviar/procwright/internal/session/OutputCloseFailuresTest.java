/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.FailureAggregation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OutputCloseFailuresTest {

    @Test
    void primaryInstalledAfterFinalizationLeavesFutureFailuresForReporting() {
        List<Throwable> reported = new ArrayList<>();
        OutputCloseFailures failures = new OutputCloseFailures((target, failure) -> reported.add(failure));
        AssertionError primary = new AssertionError("late primary");
        AssertionError stdoutFailure = new AssertionError("stdout close failed");
        AssertionError stderrFailure = new AssertionError("stderr close failed");

        failures.finish();
        failures.retainPrimary(primary);
        failures.record(stdoutFailure);
        failures.record(stderrFailure);

        assertEquals(List.of(stdoutFailure, stderrFailure), reported);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void firstFallbackRemainsObservedPrimaryAndLaterFailuresAreReported() {
        List<Throwable> reported = new ArrayList<>();
        OutputCloseFailures failures = new OutputCloseFailures((target, failure) -> reported.add(failure));
        AssertionError primary = new AssertionError("primary");
        IllegalStateException secondary = new IllegalStateException("secondary");

        failures.retainFallback(primary);
        failures.retainFallback(secondary);
        failures.finish();

        assertEquals(List.of(secondary), reported);
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void aggregateAndItsSourcesAreReportedOnceInEitherObservationOrder() {
        for (boolean aggregateFirst : List.of(true, false)) {
            List<Throwable> reported = new ArrayList<>();
            OutputCloseFailures failures = new OutputCloseFailures((target, failure) -> reported.add(failure));
            AssertionError primary = new AssertionError("primary");
            IllegalStateException first = new IllegalStateException("first");
            IllegalArgumentException second = new IllegalArgumentException("second");
            Throwable aggregate = FailureAggregation.combine(first, second, "aggregate");

            failures.retainPrimary(primary);
            if (aggregateFirst) {
                failures.record(aggregate);
                failures.record(second);
            } else {
                failures.record(second);
                failures.record(aggregate);
            }
            failures.finish();

            assertEquals(
                    aggregateFirst ? List.of(first, second) : List.of(second, first),
                    reported,
                    "observation order must be stable without duplicate source identities");
        }
    }

    @Test
    void selectedAggregateRepresentsAllOfItsSources() {
        List<Throwable> reported = new ArrayList<>();
        OutputCloseFailures failures = new OutputCloseFailures((target, failure) -> reported.add(failure));
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        Throwable aggregate = FailureAggregation.combine(first, second, "aggregate");

        failures.retainPrimary(aggregate);
        failures.record(first);
        failures.record(second);
        failures.finish();

        assertEquals(List.of(), reported);
    }

    @Test
    void reporterFailureCannotEscapeFinalization() {
        OutputCloseFailures failures = new OutputCloseFailures((target, failure) -> {
            throw new AssertionError("reporter failed");
        });
        AssertionError failure = new AssertionError("output close failed");

        assertDoesNotThrow(() -> {
            failures.record(failure);
            failures.finish();
            failures.finish();
        });
    }

    @Test
    void failureReportingUsesTheObservationThreadRatherThanTheConstructorThread() throws Exception {
        List<Throwable> constructorReports = new CopyOnWriteArrayList<>();
        List<Throwable> observationReports = new CopyOnWriteArrayList<>();
        Thread constructorThread = Thread.currentThread();
        Thread.UncaughtExceptionHandler previousHandler = constructorThread.getUncaughtExceptionHandler();
        constructorThread.setUncaughtExceptionHandler((ignored, failure) -> constructorReports.add(failure));
        OutputCloseFailures failures = new OutputCloseFailures();
        AssertionError primary = new AssertionError("primary");
        AssertionError secondary = new AssertionError("secondary");
        Thread observer = new Thread(
                () -> {
                    failures.retainPrimary(primary);
                    failures.record(secondary);
                },
                "output-failure-observer");
        observer.setUncaughtExceptionHandler((ignored, failure) -> observationReports.add(failure));
        try {
            observer.start();
            observer.join(TimeUnit.SECONDS.toMillis(1));
            assertTrue(!observer.isAlive());

            failures.finish();
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(List.of(), constructorReports);
            assertEquals(List.of(secondary), observationReports);
        } finally {
            constructorThread.setUncaughtExceptionHandler(previousHandler);
        }
    }
}
