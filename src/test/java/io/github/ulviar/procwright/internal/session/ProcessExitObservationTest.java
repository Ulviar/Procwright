/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProcessExitObservationTest {

    @Test
    void publicationAndRemovalRaceIsTransactionalAndBounded() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int removed = 0;
        int delivered = 0;
        try {
            for (int iteration = 0; iteration < 1_000; iteration++) {
                DefaultSession.ProcessExitObservation observation = new DefaultSession.ProcessExitObservation();
                AtomicReference<OptionalInt> cache = new AtomicReference<>(OptionalInt.empty());
                DefaultSession.ProcessExitObservation.Registration registration = observation.subscribe(cache);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> publication = executor.submit(() -> {
                    await(start);
                    observation.publish(23);
                });
                Future<?> removal = executor.submit(() -> {
                    await(start);
                    registration.close();
                });

                start.countDown();
                get(publication);
                get(removal);
                registration.close();

                assertEquals(0, observation.subscriberCount());
                assertTrue(observation.deliveryCount() == 0 || observation.deliveryCount() == 1);
                if (observation.deliveryCount() == 0) {
                    assertTrue(cache.get().isEmpty());
                    removed++;
                } else {
                    assertEquals(23, cache.get().orElseThrow());
                    delivered++;
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(1_000, removed + delivered);
    }

    @Test
    void removalAndPublicationEachWinExactlyOnce() {
        DefaultSession.ProcessExitObservation removedObservation = new DefaultSession.ProcessExitObservation();
        AtomicReference<OptionalInt> removedCache = new AtomicReference<>(OptionalInt.empty());
        DefaultSession.ProcessExitObservation.Registration removed = removedObservation.subscribe(removedCache);

        removed.close();
        removed.close();
        removedObservation.publish(17);

        assertEquals(0, removedObservation.subscriberCount());
        assertEquals(0, removedObservation.deliveryCount());
        assertTrue(removedCache.get().isEmpty());

        DefaultSession.ProcessExitObservation deliveredObservation = new DefaultSession.ProcessExitObservation();
        AtomicReference<OptionalInt> deliveredCache = new AtomicReference<>(OptionalInt.empty());
        DefaultSession.ProcessExitObservation.Registration delivered = deliveredObservation.subscribe(deliveredCache);

        deliveredObservation.publish(19);
        deliveredObservation.publish(19);
        delivered.close();

        assertEquals(0, deliveredObservation.subscriberCount());
        assertEquals(1, deliveredObservation.deliveryCount());
        assertEquals(19, deliveredCache.get().orElseThrow());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
        }
    }

    private static void get(Future<?> future) throws InterruptedException, ExecutionException, TimeoutException {
        future.get(2, TimeUnit.SECONDS);
    }
}
