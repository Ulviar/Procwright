/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

final class WorkerCloseFailureAccumulatorTest {

    @Test
    void laterErrorBecomesPrimaryAndRetainsPriorRuntimeExactlyOnce() {
        IllegalStateException runtimeFailure = new IllegalStateException("runtime close failed");
        AssertionError fatalFailure = new AssertionError("fatal close failed");
        WorkerCloseFailureAccumulator failures = new WorkerCloseFailureAccumulator();

        failures.add(runtimeFailure);
        failures.add(fatalFailure);
        failures.add(runtimeFailure);

        assertSame(fatalFailure, failures.failure());
        assertSuppressedExactlyOnce(fatalFailure, runtimeFailure);
    }

    @Test
    void firstErrorRemainsPrimaryAndRetainsLaterFailuresExactlyOnce() {
        AssertionError firstFatal = new AssertionError("first fatal close failed");
        OutOfMemoryError secondFatal = new OutOfMemoryError("second fatal close failed");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime close failed");
        WorkerCloseFailureAccumulator failures = new WorkerCloseFailureAccumulator();

        failures.add(firstFatal);
        failures.add(runtimeFailure);
        failures.add(secondFatal);
        failures.add(runtimeFailure);
        failures.add(secondFatal);

        assertSame(firstFatal, failures.failure());
        assertSuppressedExactlyOnce(firstFatal, runtimeFailure);
        assertSuppressedExactlyOnce(firstFatal, secondFatal);
    }

    @Test
    void errorPromotionDoesNotCreateSuppressionCycle() {
        AssertionError fatalFailure = new AssertionError("fatal close failed");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime close failed", fatalFailure);
        WorkerCloseFailureAccumulator failures = new WorkerCloseFailureAccumulator();

        failures.add(runtimeFailure);
        failures.add(fatalFailure);

        assertSame(fatalFailure, failures.failure());
        assertEquals(0, fatalFailure.getSuppressed().length);
        assertSame(fatalFailure, runtimeFailure.getCause());
    }

    private static void assertSuppressedExactlyOnce(Throwable primary, Throwable expected) {
        int matches = 0;
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == expected) {
                matches++;
            }
        }
        assertEquals(1, matches);
    }
}
