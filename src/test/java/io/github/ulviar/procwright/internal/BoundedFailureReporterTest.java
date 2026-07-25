/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedFailureReporterTest {

    @Test
    void bestEffortReportingContainsFailureTargetCaptureErrors() throws Exception {
        AssertionError captureFailure = new AssertionError("context loader unavailable");
        AtomicBoolean returned = new AtomicBoolean();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread source =
                new Thread(
                        () -> {
                            BoundedFailureReporter.reportBestEffort(new IllegalStateException("secondary"));
                            returned.set(true);
                        },
                        "hostile-failure-source") {
                    @Override
                    public ClassLoader getContextClassLoader() {
                        throw captureFailure;
                    }
                };
        source.setUncaughtExceptionHandler((thread, failure) -> uncaught.set(failure));

        source.start();
        source.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(source.isAlive());
        assertTrue(returned.get());
        assertNull(uncaught.get());
    }

    @Test
    void blockedCallbacksConsumeOnlyTheFixedWorkerAndQueueCapacity() throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        try {
            assertTrue(reporter.execute(Thread.currentThread(), () -> {
                firstEntered.countDown();
                awaitUninterruptibly(releaseFirst);
            }));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            assertTrue(reporter.execute(Thread.currentThread(), secondCompleted::countDown));
            assertFalse(reporter.execute(Thread.currentThread(), () -> {}));
            assertEquals(1, reporter.activeCount());
            assertEquals(1, reporter.queuedCount());
        } finally {
            releaseFirst.countDown();
        }
        assertTrue(secondCompleted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void acceptedFailureReportPreservesSourceHandlerAndErrorIdentity() throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 1);
        AssertionError expected = new AssertionError("reported failure");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicReference<Thread> observedSource = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        Thread source = new Thread(() -> {}, "failure-source");
        ClassLoader sourceLoader = new ClassLoader(null) {};
        source.setContextClassLoader(sourceLoader);
        source.setPriority(Thread.MAX_PRIORITY);
        source.setUncaughtExceptionHandler((reportedSource, failure) -> {
            observedSource.set(reportedSource);
            observed.set(failure);
            reportedSource.setName("hostile-handler-name");
            reportedSource.setContextClassLoader(new ClassLoader(null) {});
            reportedSource.setPriority(Thread.MIN_PRIORITY);
            reportedSource.interrupt();
            reported.countDown();
        });

        assertTrue(reporter.report(source, expected));

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertSame(expected, observed.get());
        assertNotSame(source, observedSource.get());
        assertEquals("failure-source", source.getName());
        assertSame(sourceLoader, source.getContextClassLoader());
        assertEquals(Thread.MAX_PRIORITY, source.getPriority());
        assertFalse(source.isInterrupted());
    }

    @Test
    void externalCallbacksUseFreshNonInheritingThreadsAcrossAcceptedTasks() throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 1);
        ThreadLocal<String> contamination = new ThreadLocal<>();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        ClassLoader expectedLoader = BoundedFailureReporterTest.class.getClassLoader();
        ClassLoader contaminatedLoader = new ClassLoader(null) {};
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        try {
            assertTrue(reporter.execute(Thread.currentThread(), () -> {
                assertNull(contamination.get());
                assertNull(inherited.get());
                contamination.set("must-die-with-thread");
                firstThread.set(Thread.currentThread());
                Thread.currentThread().setContextClassLoader(contaminatedLoader);
                Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                firstStarted.countDown();
                awaitUninterruptibly(releaseFirst);
            }));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(reporter.execute(Thread.currentThread(), () -> {
                assertNull(contamination.get(), "failure callback state crossed task ownership");
                assertNull(inherited.get(), "failure callback inherited submitting-thread state");
                assertSame(expectedLoader, Thread.currentThread().getContextClassLoader());
                assertEquals(Thread.NORM_PRIORITY, Thread.currentThread().getPriority());
                secondThread.set(Thread.currentThread());
                secondCompleted.countDown();
            }));
            releaseFirst.countDown();
            assertTrue(secondCompleted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            inherited.remove();
        }
        assertNotSame(firstThread.get(), secondThread.get());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
