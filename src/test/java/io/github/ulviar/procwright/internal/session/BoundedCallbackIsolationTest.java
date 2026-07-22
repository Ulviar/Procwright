/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedCallbackIsolationTest {

    @Test
    void eachCallbackFeatureRetainsIndependentProcessWideCapacity() throws Exception {
        List<BoundedTaskRunner.Limiter> partitions = List.of(
                BoundedTaskRunner.STREAM_LISTENERS, BoundedTaskRunner.READINESS_PROBES, BoundedTaskRunner.WORKER_HOOKS);
        assertNotSame(partitions.get(0), partitions.get(1));
        assertNotSame(partitions.get(0), partitions.get(2));
        assertNotSame(partitions.get(1), partitions.get(2));

        for (int saturatedIndex = 0; saturatedIndex < partitions.size(); saturatedIndex++) {
            BoundedTaskRunner.Limiter saturated = partitions.get(saturatedIndex);
            CountDownLatch release = new CountDownLatch(1);
            try {
                saturate(saturated, release);
                assertEquals(0, saturated.availablePermits());
                for (int candidateIndex = 0; candidateIndex < partitions.size(); candidateIndex++) {
                    if (candidateIndex == saturatedIndex) {
                        continue;
                    }
                    assertFeatureAvailable(candidateIndex);
                }
            } finally {
                release.countDown();
            }
            assertTrue(eventually(() -> saturated.availablePermits() == saturated.capacity()));
        }
    }

    @Test
    void readinessHooksAndProtocolCallbacksNeverReuseCrossOwnerThreadState() throws Exception {
        ThreadLocal<String> contamination = new ThreadLocal<>();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        try {
            assertFreshLane(BoundedTaskRunner.READINESS_PROBES.capacity() + 1, contamination, inherited, () -> {
                AtomicReference<Thread> callbackThread = new AtomicReference<>();
                ReadinessSupport.check(
                        "target",
                        ignored -> callbackThread.set(assertCleanCallbackThread(contamination, inherited)),
                        Duration.ofSeconds(1),
                        () -> {});
                return callbackThread.get();
            });
            assertFreshLane(
                    BoundedTaskRunner.WORKER_HOOKS.capacity() + 1,
                    contamination,
                    inherited,
                    () -> WorkerHookSupport.run(
                            "procwright-clean-worker-hook-test-",
                            Duration.ofSeconds(1),
                            () -> assertCleanCallbackThread(contamination, inherited),
                            () -> new IllegalStateException("worker hook timed out"),
                            interruption -> new IllegalStateException("worker hook interrupted", interruption),
                            failure -> new IllegalStateException("worker hook failed", failure)),
                    "worker hook");
            assertFreshLane(
                    BoundedTaskRunner.PROTOCOL_CALLBACKS.capacity() + 1,
                    contamination,
                    inherited,
                    () -> BoundedTaskRunner.run(
                            BoundedTaskRunner.PROTOCOL_CALLBACKS,
                            "procwright-clean-protocol-callback-test-",
                            deadline(Duration.ofSeconds(1)),
                            () -> assertCleanCallbackThread(contamination, inherited)),
                    "protocol callback");
        } finally {
            inherited.remove();
        }
    }

    private static void assertFreshLane(
            int invocations,
            ThreadLocal<String> contamination,
            InheritableThreadLocal<String> inherited,
            ThreadCall call)
            throws Exception {
        assertFreshLane(invocations, contamination, inherited, call, "readiness probe");
    }

    private static void assertFreshLane(
            int invocations,
            ThreadLocal<String> contamination,
            InheritableThreadLocal<String> inherited,
            ThreadCall call,
            String lane)
            throws Exception {
        Set<Thread> threads = new HashSet<>();
        for (int invocation = 0; invocation < invocations; invocation++) {
            assertTrue(threads.add(call.run()), lane + " reused a thread across callback owners");
            contamination.remove();
        }
        assertEquals(invocations, threads.size());
        assertEquals("caller-state", inherited.get());
    }

    private static Thread assertCleanCallbackThread(
            ThreadLocal<String> contamination, InheritableThreadLocal<String> inherited) {
        assertNull(contamination.get(), "callback ThreadLocal state crossed invocation ownership");
        assertNull(inherited.get(), "callback inherited caller ThreadLocal state");
        contamination.set("must-die-with-thread");
        return Thread.currentThread();
    }

    private static void assertFeatureAvailable(int featureIndex) throws Exception {
        if (featureIndex == 0) {
            assertEquals(
                    "available",
                    BoundedTaskRunner.run(
                            BoundedTaskRunner.STREAM_LISTENERS,
                            "procwright-isolated-listener-test-",
                            deadline(Duration.ofSeconds(1)),
                            () -> "available"));
            return;
        }
        if (featureIndex == 1) {
            AtomicBoolean invoked = new AtomicBoolean();
            ReadinessSupport.check("target", ignored -> invoked.set(true), Duration.ofSeconds(1), () -> {});
            assertTrue(invoked.get());
            return;
        }
        assertEquals(
                "available",
                WorkerHookSupport.run(
                        "procwright-isolated-worker-hook-test-",
                        Duration.ofSeconds(1),
                        () -> "available",
                        () -> new IllegalStateException("worker hook timed out"),
                        interruption -> new IllegalStateException("worker hook interrupted", interruption),
                        failure -> new IllegalStateException("worker hook failed", failure)));
    }

    private static void saturate(BoundedTaskRunner.Limiter limiter, CountDownLatch release) throws Exception {
        int capacity = limiter.capacity();
        CountDownLatch started = new CountDownLatch(capacity);
        ExecutorService callers = Executors.newFixedThreadPool(capacity);
        try {
            List<BoundedTaskRunner.CancellationSignal> cancellations = java.util.stream.IntStream.range(0, capacity)
                    .mapToObj(index -> new BoundedTaskRunner.CancellationSignal())
                    .toList();
            List<Future<Throwable>> outcomes = java.util.stream.IntStream.range(0, capacity)
                    .mapToObj(index -> callers.submit(() -> captureFailure(() -> BoundedTaskRunner.run(
                            limiter,
                            "procwright-saturated-callback-test-",
                            deadline(Duration.ofHours(1)),
                            cancellations.get(index),
                            () -> {
                                started.countDown();
                                awaitIgnoringInterrupts(release);
                                return null;
                            }))))
                    .toList();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            cancellations.forEach(BoundedTaskRunner.CancellationSignal::cancel);
            for (Future<Throwable> outcome : outcomes) {
                assertTrue(outcome.get(1, TimeUnit.SECONDS) instanceof BoundedTaskRunner.TaskCancelledException);
            }
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static Throwable captureFailure(ThrowingOperation operation) {
        try {
            operation.run();
            throw new AssertionError("expected operation to fail");
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean() && deadline - System.nanoTime() > 0) {
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
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

    @FunctionalInterface
    private interface ThreadCall {

        Thread run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingOperation {

        void run() throws Throwable;
    }
}
