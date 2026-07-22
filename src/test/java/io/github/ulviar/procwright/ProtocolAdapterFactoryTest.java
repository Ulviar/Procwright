/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class ProtocolAdapterFactoryTest {

    @Test
    void concurrentAdapterCreationIsNotSerializedByTheLibrary() throws Exception {
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maximumActiveCalls = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch bothInsideFactory = new CountDownLatch(2);
        Supplier<ProtocolAdapter<String, String>> factory = () -> {
            int active = activeCalls.incrementAndGet();
            maximumActiveCalls.accumulateAndGet(active, Math::max);
            bothInsideFactory.countDown();
            try {
                assertTrue(bothInsideFactory.await(1, TimeUnit.SECONDS), "factory calls did not overlap");
                return new NoopAdapter();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                activeCalls.decrementAndGet();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProtocolAdapter<String, String>> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(1, TimeUnit.SECONDS));
                return ScenarioRuntime.createProtocolAdapter(factory);
            });
            Future<ProtocolAdapter<String, String>> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(1, TimeUnit.SECONDS));
                return ScenarioRuntime.createProtocolAdapter(factory);
            });

            assertTrue(ready.await(1, TimeUnit.SECONDS));
            start.countDown();
            ProtocolAdapter<String, String> firstAdapter = first.get(2, TimeUnit.SECONDS);
            ProtocolAdapter<String, String> secondAdapter = second.get(2, TimeUnit.SECONDS);

            assertInstanceOf(NoopAdapter.class, firstAdapter);
            assertInstanceOf(NoopAdapter.class, secondAdapter);
            assertNotSame(firstAdapter, secondAdapter);
            assertEquals(2, maximumActiveCalls.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void adapterCreationPreservesFactoryFailuresAndRejectsNullResults() {
        RuntimeException runtimeFailure = new IllegalStateException("factory failure");
        assertSame(
                runtimeFailure,
                assertThrows(
                        RuntimeException.class,
                        () -> ScenarioRuntime.createProtocolAdapter(() -> {
                            throw runtimeFailure;
                        })));

        AssertionError error = new AssertionError("factory error");
        assertSame(
                error,
                assertThrows(
                        AssertionError.class,
                        () -> ScenarioRuntime.createProtocolAdapter(() -> {
                            throw error;
                        })));

        NullPointerException nullFactory =
                assertThrows(NullPointerException.class, () -> ScenarioRuntime.createProtocolAdapter(null));
        NullPointerException nullResult =
                assertThrows(NullPointerException.class, () -> ScenarioRuntime.createProtocolAdapter(() -> null));
        assertEquals("adapterFactory", nullFactory.getMessage());
        assertEquals("adapterFactory returned null", nullResult.getMessage());
    }

    private static final class NoopAdapter implements ProtocolAdapter<String, String> {
        @Override
        public void writeRequest(String request, ProtocolWriter writer) {}

        @Override
        public String readResponse(ProtocolReaders readers) {
            return "";
        }
    }
}
