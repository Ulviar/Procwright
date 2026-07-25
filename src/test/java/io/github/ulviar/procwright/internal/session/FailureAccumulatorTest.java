/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ulviar.procwright.internal.FailureAggregation;
import java.io.IOException;
import org.junit.jupiter.api.Test;

final class FailureAccumulatorTest {

    @Test
    void firstObservedFailureRemainsPrimaryWhenNoErrorWasObserved() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        FailureAccumulator failures = new FailureAccumulator();

        failures.add(first);
        failures.add(second);

        Throwable aggregate = failures.aggregateErrorFirst("multiple failures");
        assertSame(first, aggregate.getCause());
        assertSuppressedExactlyOnce(aggregate, second);
    }

    @Test
    void laterErrorBecomesPrimaryAndRetainsPriorRuntimeExactlyOnce() {
        IllegalStateException runtimeFailure = new IllegalStateException("runtime close failed");
        AssertionError fatalFailure = new AssertionError("fatal close failed");
        FailureAccumulator failures = new FailureAccumulator();

        failures.add(runtimeFailure);
        failures.add(fatalFailure);
        failures.add(runtimeFailure);

        Throwable aggregate = failures.aggregateErrorFirst("multiple failures");
        assertSame(fatalFailure, aggregate.getCause());
        assertSuppressedExactlyOnce(aggregate, runtimeFailure);
        assertEquals(0, fatalFailure.getSuppressed().length);
    }

    @Test
    void firstErrorRemainsPrimaryAndRetainsLaterFailuresExactlyOnce() {
        AssertionError firstFatal = new AssertionError("first fatal close failed");
        OutOfMemoryError secondFatal = new OutOfMemoryError("second fatal close failed");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime close failed");
        FailureAccumulator failures = new FailureAccumulator();

        failures.add(firstFatal);
        failures.add(runtimeFailure);
        failures.add(secondFatal);
        failures.add(runtimeFailure);
        failures.add(secondFatal);

        Throwable aggregate = failures.aggregateErrorFirst("multiple failures");
        assertSame(firstFatal, aggregate.getCause());
        assertSuppressedExactlyOnce(aggregate, runtimeFailure);
        assertSuppressedExactlyOnce(aggregate, secondFatal);
        assertEquals(0, firstFatal.getSuppressed().length);
    }

    @Test
    void errorPromotionRetainsPriorFailureEvenWhenItReferencesThePromotedError() {
        AssertionError fatalFailure = new AssertionError("fatal close failed");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime close failed", fatalFailure);
        FailureAccumulator failures = new FailureAccumulator();

        failures.add(runtimeFailure);
        failures.add(fatalFailure);

        Throwable aggregate = failures.aggregateErrorFirst("multiple failures");
        assertSame(fatalFailure, aggregate.getCause());
        assertSuppressedExactlyOnce(aggregate, runtimeFailure);
        assertSame(fatalFailure, runtimeFailure.getCause());
    }

    @Test
    void knownAggregateSourcesAreFlattenedBeforeFurtherFailuresAreAdded() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        AssertionError fatal = new AssertionError("fatal");
        Throwable priorAggregate =
                io.github.ulviar.procwright.internal.FailureAggregation.combine(first, second, "prior aggregate");
        FailureAccumulator failures = new FailureAccumulator();

        failures.add(priorAggregate);
        failures.add(second);
        failures.add(fatal);

        Throwable aggregate = failures.aggregateErrorFirst("multiple failures");
        assertSame(fatal, aggregate.getCause());
        assertEquals(2, aggregate.getSuppressed().length);
        assertSuppressedExactlyOnce(aggregate, first);
        assertSuppressedExactlyOnce(aggregate, second);
    }

    @Test
    void knownAggregateRetainsItsExplicitNonFirstPrimary() {
        IOException checked = new IOException("checked");
        IllegalStateException runtime = new IllegalStateException("runtime");
        Throwable priorAggregate =
                FailureAggregation.combineWithPrimary(runtime, java.util.List.of(checked, runtime), "prior aggregate");
        FailureAccumulator failures = new FailureAccumulator();

        failures.add(priorAggregate);

        Throwable aggregate = failures.aggregateErrorFirst("multiple failures");
        assertSame(runtime, aggregate.getCause());
        assertSuppressedExactlyOnce(aggregate, checked);
        assertEquals(0, checked.getSuppressed().length);
        assertEquals(0, runtime.getSuppressed().length);
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
