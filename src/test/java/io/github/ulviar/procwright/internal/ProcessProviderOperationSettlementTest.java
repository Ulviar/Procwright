/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProcessProviderOperationSettlementTest {

    @Test
    void producerWaitsForCallerAndWorkerInEitherOrder() throws Exception {
        assertProducerWaitsForBothSides(true);
        assertProducerWaitsForBothSides(false);
    }

    private static void assertProducerWaitsForBothSides(boolean callerFirst) throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationSettlement settlement = newSettlement(reporter);

        if (callerFirst) {
            settlement.resultObserved();
        } else {
            settlement.workerCompleted(null);
        }
        assertFalse(reporter.awaitSettlement(Duration.ofMillis(10)));

        if (callerFirst) {
            settlement.workerCompleted(null);
        } else {
            settlement.resultObserved();
        }
        assertTrue(reporter.awaitSettlement(Duration.ofSeconds(1)));
    }

    @Test
    void abandonedFailureRemainsUnsettledUntilItsRealReportCompletes() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        AssertionError lateFailure = new AssertionError("late provider failure");
        Thread source = new Thread(() -> {}, "provider-operation-source");
        source.setUncaughtExceptionHandler((ignored, failure) -> {
            if (failure == lateFailure) {
                reports.incrementAndGet();
                handlerEntered.countDown();
                awaitUninterruptibly(releaseHandler);
            }
        });
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationSettlement settlement = newSettlement(reporter);
        settlement.bind(source);

        try {
            settlement.abandon();
            settlement.workerCompleted(lateFailure);

            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
            assertFalse(reporter.awaitSettlement(Duration.ofMillis(20)));
            assertEquals(1, reports.get());
        } finally {
            releaseHandler.countDown();
        }
        assertTrue(reporter.awaitSettlement(Duration.ofSeconds(1)));
        assertEquals(1, reports.get());
    }

    @Test
    void ownerInducedCheckedInterruptionSettlesWithoutReporting() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        Thread source = new Thread(() -> {}, "provider-interruption-source");
        source.setUncaughtExceptionHandler((ignored, failure) -> reports.incrementAndGet());
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationSettlement settlement = newSettlement(reporter);
        settlement.bind(source);

        settlement.abandon();
        settlement.workerCompleted(new InterruptedException("owner cancellation"));

        assertTrue(reporter.awaitSettlement(Duration.ofSeconds(1)));
        assertEquals(0, reports.get());
    }

    @Test
    void interruptRequestedBeforeWorkerBindingIsDeliveredOnBind() throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationSettlement settlement = newSettlement(reporter);
        ProcessProviderOperationCancellation cancellation = new ProcessProviderOperationCancellation();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(
                () -> {
                    cancellation.bind(Thread.currentThread());
                    interrupted.set(Thread.currentThread().isInterrupted());
                    Thread.interrupted();
                    cancellation.unbind(Thread.currentThread());
                    settlement.workerCompleted(null);
                },
                "provider-operation-interrupt-handoff");

        cancellation.interrupt();
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(1));
        settlement.resultObserved();

        assertFalse(worker.isAlive());
        assertTrue(interrupted.get());
        assertTrue(reporter.awaitSettlement(Duration.ofSeconds(1)));
    }

    private static ProcessProviderOperationSettlement newSettlement(BoundedFailureReporter reporter) {
        return new ProcessProviderOperationSettlement(
                new LateTaskFailureReporter(reporter), reporter.registerProducer());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean restoreInterrupt = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }
}
