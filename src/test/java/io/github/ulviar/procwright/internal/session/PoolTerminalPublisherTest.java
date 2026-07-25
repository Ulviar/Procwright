/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PoolTerminalPublisherTest {

    private static final ThreadLocal<String> ORDINARY_STATE = new ThreadLocal<>();
    private static final InheritableThreadLocal<String> INHERITED_STATE = new InheritableThreadLocal<>();

    @Test
    void blockedContinuationDoesNotDelayAnotherAcceptedPool() throws Exception {
        PoolTerminalPublisher.Capacity capacity = new PoolTerminalPublisher.Capacity(2);
        PoolDrain first = drain(capacity);
        PoolDrain second = drain(capacity);
        CountDownLatch firstCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        CompletableFuture<Void> firstCallback = first.view().thenRun(() -> {
            firstCallbackEntered.countDown();
            awaitIgnoringInterrupt(releaseFirstCallback);
        });
        try {
            assertThrows(RejectedExecutionException.class, capacity::reserve);
            claimAndPublish(first, null);
            assertTrue(firstCallbackEntered.await(1, TimeUnit.SECONDS));

            CompletableFuture<Void> secondView = second.view();
            claimAndPublish(second, null);

            secondView.get(1, TimeUnit.SECONDS);
            assertFalse(firstCallback.isDone());
        } finally {
            releaseFirstCallback.countDown();
            firstCallback.get(1, TimeUnit.SECONDS);
        }
        PoolDrain recovered = drainEventually(capacity, Duration.ofSeconds(1));
        claimAndPublish(recovered, null);
        recovered.view().get(1, TimeUnit.SECONDS);
    }

    @Test
    void ownerDoesNotInheritOrCarryThreadStateAcrossPools() throws Exception {
        PoolTerminalPublisher.Capacity capacity = new PoolTerminalPublisher.Capacity(1);
        INHERITED_STATE.set("opening-thread");
        AtomicReference<Thread> firstOwner = new AtomicReference<>();
        AtomicReference<Integer> uncontaminatedPriority = new AtomicReference<>();
        AtomicReference<ClassLoader> contaminatedLoader = new AtomicReference<>();
        Thread.UncaughtExceptionHandler contaminatedHandler = (thread, failure) -> {};
        try {
            PoolDrain first = drain(capacity);
            CompletableFuture<Void> firstCallback = first.view().thenRun(() -> {
                Thread owner = Thread.currentThread();
                firstOwner.set(owner);
                assertNull(ORDINARY_STATE.get());
                assertNull(INHERITED_STATE.get());
                uncontaminatedPriority.set(owner.getPriority());
                ORDINARY_STATE.set("first-pool");
                INHERITED_STATE.set("first-pool");
                ClassLoader loader = new ClassLoader() {};
                contaminatedLoader.set(loader);
                owner.setContextClassLoader(loader);
                owner.setPriority(Thread.MIN_PRIORITY);
                owner.setName("contaminated-pool-owner");
                owner.setUncaughtExceptionHandler(contaminatedHandler);
                owner.interrupt();
            });
            claimAndPublish(first, null);
            firstCallback.get(1, TimeUnit.SECONDS);

            AtomicReference<OwnerState> secondState = new AtomicReference<>();
            PoolDrain second = drainEventually(capacity, Duration.ofSeconds(1));
            CompletableFuture<Void> secondCallback = second.view().thenRun(() -> {
                Thread owner = Thread.currentThread();
                secondState.set(new OwnerState(
                        owner,
                        ORDINARY_STATE.get(),
                        INHERITED_STATE.get(),
                        owner.getContextClassLoader(),
                        owner.getPriority(),
                        owner.getName(),
                        owner.getUncaughtExceptionHandler(),
                        owner.isInterrupted()));
            });
            claimAndPublish(second, null);
            secondCallback.get(1, TimeUnit.SECONDS);

            OwnerState observed = secondState.get();
            assertNotSame(firstOwner.get(), observed.thread());
            assertNull(observed.ordinaryState());
            assertNull(observed.inheritedState());
            assertNotSame(contaminatedLoader.get(), observed.contextClassLoader());
            assertEquals(uncontaminatedPriority.get(), observed.priority());
            assertTrue(observed.name().startsWith("procwright-pool-terminal-"));
            assertNotSame(contaminatedHandler, observed.uncaughtExceptionHandler());
            assertFalse(observed.interrupted());
        } finally {
            INHERITED_STATE.remove();
        }
    }

    @Test
    void capacityIsHeldUntilSynchronousContinuationPhysicallyExits() throws Exception {
        PoolTerminalPublisher.Capacity capacity = new PoolTerminalPublisher.Capacity(1);
        PoolDrain drain = drain(capacity);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CompletableFuture<Void> callback = drain.view().thenRun(() -> {
            callbackEntered.countDown();
            awaitIgnoringInterrupt(releaseCallback);
        });
        try {
            claimAndPublish(drain, null);
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class, capacity::reserve);
        } finally {
            releaseCallback.countDown();
            callback.get(1, TimeUnit.SECONDS);
        }

        PoolDrain recovered = drainEventually(capacity, Duration.ofSeconds(1));
        claimAndPublish(recovered, null);
        recovered.view().get(1, TimeUnit.SECONDS);
    }

    @Test
    void abortedConstructionReleasesReservedCapacity() throws Exception {
        PoolTerminalPublisher.Capacity capacity = new PoolTerminalPublisher.Capacity(1);
        PoolTerminalPublisher abandoned = capacity.reserve();
        assertThrows(RejectedExecutionException.class, capacity::reserve);

        abandoned.abort();

        PoolDrain recovered = drainEventually(capacity, Duration.ofSeconds(1));
        claimAndPublish(recovered, null);
        recovered.view().get(1, TimeUnit.SECONDS);
    }

    private static PoolDrain drain(PoolTerminalPublisher.Capacity capacity) {
        return new PoolDrain(capacity.reserve());
    }

    private static void claimAndPublish(PoolDrain drain, Throwable failure) {
        PoolDrain.Publication publication = drain.claim(failure);
        assertNotNull(publication);
        publication.publish();
    }

    private static PoolDrain drainEventually(PoolTerminalPublisher.Capacity capacity, Duration timeout)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                return drain(capacity);
            } catch (RejectedExecutionException failure) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw failure;
                }
                Thread.sleep(1);
            }
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record OwnerState(
            Thread thread,
            String ordinaryState,
            String inheritedState,
            ClassLoader contextClassLoader,
            int priority,
            String name,
            Thread.UncaughtExceptionHandler uncaughtExceptionHandler,
            boolean interrupted) {}
}
