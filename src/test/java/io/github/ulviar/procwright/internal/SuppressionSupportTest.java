/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SuppressionSupportTest {

    @Test
    void selfSuppressionAndRepeatedSecondaryAreIdentitySafe() {
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");

        SuppressionSupport.attach(primary, primary);
        SuppressionSupport.attach(primary, secondary);
        SuppressionSupport.attach(primary, secondary);

        assertEquals(1, primary.getSuppressed().length);
        assertSame(secondary, primary.getSuppressed()[0]);
    }

    @Test
    void combineKeepsTheFirstFailureAsPrimary() {
        AssertionError primary = new AssertionError("primary");
        IllegalStateException secondary = new IllegalStateException("secondary");

        Throwable combined = SuppressionSupport.combine(null, primary);
        combined = SuppressionSupport.combine(combined, secondary);
        combined = SuppressionSupport.combine(combined, primary);

        assertSame(primary, combined);
        assertEquals(1, primary.getSuppressed().length);
        assertSame(secondary, primary.getSuppressed()[0]);
    }

    @Test
    void secondaryWhoseCauseGraphReachesPrimaryIsNotAttached() {
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        primary.initCause(secondary);
        secondary.initCause(primary);

        SuppressionSupport.attach(primary, secondary);

        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void secondaryWhoseSuppressedGraphReachesPrimaryIsNotAttached() {
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        AssertionError intermediate = new AssertionError("intermediate");
        secondary.addSuppressed(intermediate);
        intermediate.addSuppressed(primary);

        SuppressionSupport.attach(primary, secondary);

        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void cyclicSecondaryGraphWithoutPrimaryIsTraversedOnceAndAttached() {
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        AssertionError cycle = new AssertionError("cycle");
        secondary.addSuppressed(cycle);
        cycle.addSuppressed(secondary);

        SuppressionSupport.attach(primary, secondary);

        assertEquals(1, primary.getSuppressed().length);
        assertSame(secondary, primary.getSuppressed()[0]);
    }

    @Test
    void hostileCauseTraversalCannotMaskPrimary() {
        IllegalStateException primary = new IllegalStateException("primary");
        AssertionError traversalFailure = new AssertionError("getCause failed");
        Throwable secondary = new HostileCauseFailure(traversalFailure);

        SuppressionSupport.attach(primary, secondary);

        assertEquals(0, primary.getSuppressed().length);
        assertSame(traversalFailure, ((HostileCauseFailure) secondary).traversalFailure());
        assertFalse(SuppressionSupport.containsInterruption(secondary));
    }

    @Test
    void hostilePrimaryCauseTraversalSkipsOptionalSuppressionWithoutMaskingPrimary() {
        AssertionError traversalFailure = new AssertionError("primary getCause failed");
        Throwable primary = new HostileCauseFailure(traversalFailure);
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");

        SuppressionSupport.attach(primary, secondary);

        assertEquals(0, primary.getSuppressed().length);
        assertSame(traversalFailure, ((HostileCauseFailure) primary).traversalFailure());
    }

    @Test
    void secondaryAlreadyReachableFromPrimaryGraphIsNotAttachedAgain() {
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        IllegalStateException primary = new IllegalStateException("primary", secondary);

        SuppressionSupport.attach(primary, secondary);

        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void directAttachmentRecordsAnAlreadyTransitivelyReachableOwnedFailure() {
        IllegalArgumentException secondary = new IllegalArgumentException("secondary");
        IllegalStateException intermediate = new IllegalStateException("intermediate", secondary);
        AssertionError primary = new AssertionError("primary");
        primary.addSuppressed(intermediate);

        SuppressionSupport.attachDirect(primary, secondary);
        SuppressionSupport.attachDirect(primary, secondary);

        assertEquals(2, primary.getSuppressed().length);
        assertSame(intermediate, primary.getSuppressed()[0]);
        assertSame(secondary, primary.getSuppressed()[1]);
    }

    @Test
    void directAttachmentStillRejectsASecondaryGraphThatReachesPrimary() {
        IllegalStateException primary = new IllegalStateException("primary");
        IllegalArgumentException secondary = new IllegalArgumentException("secondary", primary);

        SuppressionSupport.attachDirect(primary, secondary);

        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void interruptionSearchTerminatesOnCauseCycleAndFindsReachableInterruption() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);

        assertFalse(SuppressionSupport.containsInterruption(first));

        InterruptedException interruption = new InterruptedException("interrupted");
        IllegalStateException wrapping = new IllegalStateException("wrapping", interruption);
        assertTrue(SuppressionSupport.containsInterruption(wrapping));
    }

    @Test
    void concurrentInverseAttachmentsCannotDeadlockOrCreateACycle() throws Exception {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstToSecond = executor.submit(() -> {
                ready.countDown();
                start.await();
                SuppressionSupport.attach(first, second);
                return null;
            });
            Future<?> secondToFirst = executor.submit(() -> {
                ready.countDown();
                start.await();
                SuppressionSupport.attach(second, first);
                return null;
            });
            assertTrue(ready.await(1, TimeUnit.SECONDS));

            start.countDown();
            firstToSecond.get(1, TimeUnit.SECONDS);
            secondToFirst.get(1, TimeUnit.SECONDS);

            int firstEdges = identityCount(first.getSuppressed(), second);
            int secondEdges = identityCount(second.getSuppressed(), first);
            assertEquals(1, firstEdges + secondEdges);
            assertFalse(firstEdges == 1 && secondEdges == 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static int identityCount(Throwable[] failures, Throwable expected) {
        int count = 0;
        for (Throwable failure : failures) {
            if (failure == expected) {
                count++;
            }
        }
        return count;
    }

    private static final class HostileCauseFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final AssertionError traversalFailure;

        private HostileCauseFailure(AssertionError traversalFailure) {
            super("hostile cause");
            this.traversalFailure = traversalFailure;
        }

        @Override
        public synchronized Throwable getCause() {
            throw traversalFailure;
        }

        private AssertionError traversalFailure() {
            return traversalFailure;
        }
    }
}
