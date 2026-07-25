/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.StreamChunk;
import io.github.ulviar.procwright.session.StreamSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class StreamListenerDispatcherTest {

    @Test
    void serializesConcurrentDeliveriesWithoutExposingItsLock() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger entries = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamListenerDispatcher dispatcher = new StreamListenerDispatcher(chunk -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                if (entries.incrementAndGet() == 1) {
                    firstEntered.countDown();
                    awaitUninterruptibly(releaseFirst);
                } else {
                    secondEntered.countDown();
                }
            } finally {
                active.decrementAndGet();
            }
        });

        Thread first = deliver(dispatcher, new StreamChunk(StreamSource.STDOUT, "one"), failure);
        Thread second = null;
        try {
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            second = deliver(dispatcher, new StreamChunk(StreamSource.STDERR, "two"), failure);

            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            join(first);
            join(second);

            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(2, entries.get());
            assertEquals(1, maxActive.get());
        } finally {
            releaseFirst.countDown();
            dispatcher.stop();
            first.join(1_000);
            if (second != null) {
                second.join(1_000);
            }
        }
    }

    private static Thread deliver(
            StreamListenerDispatcher dispatcher, StreamChunk chunk, AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().start(() -> {
            try {
                dispatcher.deliver(chunk, () -> true);
            } catch (Throwable deliveryFailure) {
                failure.compareAndSet(null, deliveryFailure);
            }
        });
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(1_000);
        assertFalse(thread.isAlive(), "delivery thread did not stop");
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
