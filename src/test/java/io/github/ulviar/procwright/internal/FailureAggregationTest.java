/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FailureAggregationTest {

    @Test
    void zeroOrOneFailureRetainsTheOriginalIdentity() {
        IOException failure = new IOException("close failed");

        assertSame(failure, FailureAggregation.combine(null, failure, "aggregate"));
        assertSame(failure, FailureAggregation.combine(failure, null, "aggregate"));
        assertSame(failure, FailureAggregation.combine(failure, failure, "aggregate"));
    }

    @Test
    void multipleFailuresProduceANewStableGraphWithoutMutatingEitherSource() {
        IOException checked = new IOException("checked");
        IllegalStateException runtime = new IllegalStateException("runtime");

        Throwable aggregate = FailureAggregation.combine(checked, runtime, "aggregate");

        assertInstanceOf(Exception.class, aggregate);
        assertEquals("aggregate", aggregate.getMessage());
        assertSame(checked, aggregate.getCause());
        assertEquals(List.of(runtime), List.of(aggregate.getSuppressed()));
        assertEquals(0, checked.getSuppressed().length);
        assertEquals(0, runtime.getSuppressed().length);
    }

    @Test
    void defaultAggregationPreservesTheFirstFailurePolicy() {
        IllegalStateException runtime = new IllegalStateException("runtime");
        AssertionError fatal = new AssertionError("fatal");

        Throwable aggregate = FailureAggregation.combine(runtime, fatal, "aggregate");

        assertInstanceOf(RuntimeException.class, aggregate);
        assertSame(runtime, aggregate.getCause());
        assertEquals(List.of(fatal), List.of(aggregate.getSuppressed()));
    }

    @Test
    void ownerCanSelectFatalPrecedenceExplicitly() {
        IllegalStateException runtime = new IllegalStateException("runtime");
        AssertionError fatal = new AssertionError("fatal");

        Throwable aggregate = FailureAggregation.combineWithPrimary(fatal, List.of(runtime, fatal), "aggregate");

        assertInstanceOf(Error.class, aggregate);
        assertSame(fatal, aggregate.getCause());
        assertEquals(List.of(runtime), List.of(aggregate.getSuppressed()));
    }

    @Test
    void repeatedIdentityDoesNotCreateAnAggregate() {
        AssertionError failure = new AssertionError("shared");

        assertSame(failure, FailureAggregation.combine(List.of(failure, failure), "aggregate"));
    }

    @Test
    void singleKnownAggregateRetainsItsPublishedIdentity() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        Throwable aggregate = FailureAggregation.combine(first, second, "aggregate");

        assertSame(aggregate, FailureAggregation.combine(List.of(aggregate), "outer"));
    }

    @Test
    void combiningAKnownAggregateFlattensItsSourcesWithoutRepeatingIdentities() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        IOException third = new IOException("third");
        Throwable inner = FailureAggregation.combine(first, second, "inner");

        Throwable outer = FailureAggregation.combine(List.of(inner, second, third), "outer");

        assertSame(first, outer.getCause());
        assertEquals(List.of(second, third), List.of(outer.getSuppressed()));
        assertEquals(0, first.getSuppressed().length);
        assertEquals(0, second.getSuppressed().length);
        assertEquals(0, third.getSuppressed().length);
    }

    @Test
    void combiningAKnownAggregateRetainsItsExplicitFatalPrimary() {
        IllegalStateException first = new IllegalStateException("first");
        AssertionError fatal = new AssertionError("fatal");
        IOException third = new IOException("third");
        Throwable inner = FailureAggregation.combineWithPrimary(fatal, List.of(first, fatal), "inner");

        Throwable outer = FailureAggregation.combine(List.of(inner, third), "outer");

        assertInstanceOf(Error.class, outer);
        assertSame(fatal, outer.getCause());
        assertEquals(List.of(first, third), List.of(outer.getSuppressed()));
        assertEquals(0, first.getSuppressed().length);
        assertEquals(0, fatal.getSuppressed().length);
        assertEquals(0, third.getSuppressed().length);
    }

    @Test
    void explicitPrimaryFromAKnownAggregateResolvesToItsPrimarySource() {
        IllegalStateException first = new IllegalStateException("first");
        AssertionError fatal = new AssertionError("fatal");
        Throwable inner = FailureAggregation.combineWithPrimary(fatal, List.of(first, fatal), "inner");

        Throwable outer = FailureAggregation.combineWithPrimary(inner, List.of(first, inner), "outer");

        assertInstanceOf(Error.class, outer);
        assertSame(fatal, outer.getCause());
        assertEquals(List.of(first), List.of(outer.getSuppressed()));
    }
}
