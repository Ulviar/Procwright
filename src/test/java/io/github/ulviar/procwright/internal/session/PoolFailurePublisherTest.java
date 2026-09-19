/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PoolFailurePublisherTest {

    @Test
    void notificationUsesTheCapturedFailureDestination() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Thread> observedSource = new AtomicReference<>();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        AtomicInteger replacementCalls = new AtomicInteger();
        Thread source = Thread.ofVirtual().name("pool-failure-source").unstarted(() -> {});
        source.setUncaughtExceptionHandler((thread, failure) -> {
            observedSource.set(thread);
            observedFailure.set(failure);
            delivered.countDown();
        });
        AssertionError failure = new AssertionError("late worker failure");
        FailureReport report = PoolFailurePublisher.capture(source, failure);
        source.setUncaughtExceptionHandler((thread, ignored) -> replacementCalls.incrementAndGet());

        new PoolFailurePublisher(PoolFailurePublisher::reportBounded).publish(report);

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertSame(failure, observedFailure.get());
        assertEquals(source.getName(), observedSource.get().getName());
        assertNotSame(source, observedSource.get());
        assertEquals(0, replacementCalls.get());
    }

    @Test
    void saturatedReportsCannotDelaySubmissionOrMandatoryRetirement() throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 1);
        PoolFailurePublisher publisher =
                new PoolFailurePublisher(report -> reporter.report(report.failureTarget(), report.failure()));
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Thread source = Thread.ofVirtual().name("blocked-pool-failure-source").unstarted(() -> {});
        source.setUncaughtExceptionHandler((thread, failure) -> {
            calls.incrementAndGet();
            handlerEntered.countDown();
            WorkerPoolControllerTestSupport.awaitIgnoringInterrupt(releaseHandler);
            delivered.countDown();
        });
        FailureReport report = PoolFailurePublisher.capture(source, new IllegalStateException("late close failed"));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            publisher.publish(report);
            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));

            caller.submit(() -> publisher.publishAll(List.of(report, report))).get(1, TimeUnit.SECONDS);
            PoolLifecycleDispatcher.executeRetirementBatch(retired::countDown);

            assertTrue(retired.await(1, TimeUnit.SECONDS));
            assertEquals(1L, releaseHandler.getCount(), "notification handler must still be blocked");
            assertEquals(1, reporter.queuedCount(), "excess reports must be dropped without another queue");
        } finally {
            releaseHandler.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
    }

    @Test
    void unavailableNotificationInfrastructureDoesNotReplaceTheLifecycleOutcome() {
        FailureReport report =
                PoolFailurePublisher.capture(Thread.currentThread(), new IllegalStateException("late worker failure"));
        PoolFailurePublisher publisher = new PoolFailurePublisher(ignored -> {
            throw new AssertionError("notification thread unavailable");
        });

        assertDoesNotThrow(() -> publisher.publishAll(List.of(report, report)));
    }
}
