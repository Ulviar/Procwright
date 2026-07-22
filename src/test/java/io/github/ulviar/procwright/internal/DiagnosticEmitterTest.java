/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DiagnosticEmitterTest {

    @Test
    void fourSelfEmittingDestinationsCannotStarveAFifthAcceptedDestination() throws Exception {
        int hostileDestinations = 4;
        CountDownLatch hostileStarted = new CountDownLatch(hostileDestinations);
        CountDownLatch fifthDelivered = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger maximumDepth = new AtomicInteger();
        ThreadLocal<Integer> callbackDepth = ThreadLocal.withInitial(() -> 0);
        List<AtomicReference<DiagnosticEmitter>> references = new java.util.ArrayList<>();
        try {
            for (int destination = 0; destination < hostileDestinations; destination++) {
                AtomicReference<DiagnosticEmitter> reference = new AtomicReference<>();
                references.add(reference);
                DiagnosticEmitter emitter = DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(event -> {
                            int depth = callbackDepth.get() + 1;
                            callbackDepth.set(depth);
                            maximumDepth.accumulateAndGet(depth, Math::max);
                            try {
                                hostileStarted.countDown();
                                if (!stop.get()) {
                                    reference.get().emit(DiagnosticEventType.COMMAND_PREPARED);
                                }
                            } finally {
                                callbackDepth.set(depth - 1);
                            }
                        }),
                        "hostile-" + destination,
                        CommandEcho.empty());
                reference.set(emitter);
                emitter.emit(DiagnosticEventType.COMMAND_PREPARED);
            }
            assertTrue(hostileStarted.await(1, TimeUnit.SECONDS));

            DiagnosticEmitter fifth = DiagnosticEmitter.of(
                    DiagnosticsSettings.disabled().withListener(event -> fifthDelivered.countDown()),
                    "fifth",
                    CommandEcho.empty());
            fifth.emit(DiagnosticEventType.COMMAND_PREPARED);

            assertTrue(fifthDelivered.await(1, TimeUnit.SECONDS));
            assertEquals(1, maximumDepth.get(), "self-emission must enqueue rather than recurse");
        } finally {
            stop.set(true);
        }
    }

    @Test
    void listenerReceivesLifecycleEventsInEmitOrder() throws Exception {
        for (int run = 0; run < 200; run++) {
            CopyOnWriteArrayList<DiagnosticEventType> delivered = new CopyOnWriteArrayList<>();
            CountDownLatch deliveries = new CountDownLatch(3);
            DiagnosticEmitter emitter = DiagnosticEmitter.of(
                    DiagnosticsSettings.disabled().withListener(event -> {
                        delivered.add(event.type());
                        deliveries.countDown();
                    }),
                    "run",
                    CommandEcho.empty());

            emitter.emit(DiagnosticEventType.COMMAND_PREPARED);
            emitter.emit(DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", "42"));
            emitter.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));

            assertTrue(deliveries.await(2, TimeUnit.SECONDS));
            assertEquals(
                    List.of(
                            DiagnosticEventType.COMMAND_PREPARED,
                            DiagnosticEventType.PROCESS_STARTED,
                            DiagnosticEventType.PROCESS_EXITED),
                    List.copyOf(delivered),
                    "events must arrive in lifecycle order on run " + run);
        }
    }

    @Test
    void listenerFailureDoesNotDropOrReorderLaterEvents() throws Exception {
        CopyOnWriteArrayList<DiagnosticEventType> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch deliveries = new CountDownLatch(2);
        DiagnosticEmitter emitter = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    if (event.type() == DiagnosticEventType.PROCESS_STARTED) {
                        throw new AssertionError("listener failed");
                    }
                    delivered.add(event.type());
                    deliveries.countDown();
                }),
                "run",
                CommandEcho.empty());

        emitter.emit(DiagnosticEventType.COMMAND_PREPARED);
        emitter.emit(DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", "42"));
        emitter.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));

        assertTrue(deliveries.await(2, TimeUnit.SECONDS));
        assertEquals(
                List.of(DiagnosticEventType.COMMAND_PREPARED, DiagnosticEventType.PROCESS_EXITED),
                List.copyOf(delivered));
    }

    @Test
    void listenerAndTranscriptSinkAreIsolatedFromEachOther() throws Exception {
        CopyOnWriteArrayList<DiagnosticEvent> recorded = new CopyOnWriteArrayList<>();
        CountDownLatch deliveries = new CountDownLatch(2);
        DiagnosticEmitter emitter = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled()
                        .withListener(event -> {
                            throw new AssertionError("listener failed");
                        })
                        .withTranscriptSink(event -> {
                            recorded.add(event);
                            deliveries.countDown();
                        }),
                "run",
                CommandEcho.empty());

        emitter.emit(DiagnosticEventType.COMMAND_PREPARED);
        emitter.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));

        assertTrue(deliveries.await(2, TimeUnit.SECONDS));
        assertEquals(DiagnosticEventType.COMMAND_PREPARED, recorded.get(0).type());
        assertEquals(DiagnosticEventType.PROCESS_EXITED, recorded.get(1).type());
    }

    @Test
    void processFailureIsDeliveredExactlyOnceBeforeLaterLifecycleEvents() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch exited = new CountDownLatch(1);
        DiagnosticEmitter emitter = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        failures.incrementAndGet();
                    }
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        exited.countDown();
                    }
                }),
                "run",
                CommandEcho.empty());

        emitter.emit(
                DiagnosticEventType.PROCESS_FAILED,
                DiagnosticEmitter.attributes("error", IllegalStateException.class.getName()));
        emitter.emit(
                DiagnosticEventType.PROCESS_FAILED,
                DiagnosticEmitter.attributes("error", AssertionError.class.getName()));
        emitter.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));

        assertTrue(exited.await(2, TimeUnit.SECONDS));
        assertEquals(1, failures.get());
    }

    @Test
    void failureAttributeFallsBackToTheNearestBoundedThrowableType() {
        Throwable failure = new FailureNamespaceSegmentWhoseNameIsDeliberatelyLongOne
                .FailureNamespaceSegmentWhoseNameIsDeliberatelyLongTwo
                .FailureNamespaceSegmentWhoseNameIsDeliberatelyLongThree.ExcessivelyNamedFailure();

        java.util.Map<String, String> attributes = DiagnosticEmitter.failureAttributes(failure);

        assertEquals(RuntimeException.class.getName(), attributes.get("error"));
        assertTrue(attributes.get("error").length() <= DiagnosticEmitter.MAX_ERROR_ATTRIBUTE_LENGTH);
        assertEquals(attributes, DiagnosticAttributeSchema.validate(DiagnosticEventType.PROCESS_FAILED, attributes));
    }

    @Test
    void hiddenFailureClassNameFallsBackToAStableSchemaSafeSuperclass() throws Throwable {
        byte[] classBytes;
        try (InputStream input = HiddenFailureTemplate.class.getResourceAsStream("HiddenFailureTemplate.class")) {
            if (input == null) {
                throw new IOException("Hidden failure template class bytes are unavailable");
            }
            classBytes = input.readAllBytes();
        }
        MethodHandles.Lookup hiddenLookup = MethodHandles.lookup().defineHiddenClass(classBytes, true);
        Throwable hiddenFailure = (Throwable) hiddenLookup
                .findConstructor(hiddenLookup.lookupClass(), MethodType.methodType(void.class))
                .invoke();

        java.util.Map<String, String> attributes = DiagnosticEmitter.failureAttributes(hiddenFailure);

        assertTrue(hiddenFailure.getClass().isHidden());
        assertTrue(hiddenFailure.getClass().getName().contains("/"));
        assertEquals(AssertionError.class.getName(), attributes.get("error"));
        assertEquals(attributes, DiagnosticAttributeSchema.validate(DiagnosticEventType.PROCESS_FAILED, attributes));
    }

    @Test
    void processFailureEmissionNeverReplacesTheOperationPrimaryWhenEventConstructionFails() {
        AssertionError primaryFailure = new AssertionError("operation failed");
        AssertionError diagnosticFailure = new AssertionError("diagnostic construction failed");
        DiagnosticEmitter emitter = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(ignored -> {}),
                "run",
                CommandEcho.empty(),
                (type, runId, timestamp, scenario, command, attributes) -> {
                    throw diagnosticFailure;
                });

        emitter.emitProcessFailure(primaryFailure);

        assertEquals(1, primaryFailure.getSuppressed().length);
        assertSame(diagnosticFailure, primaryFailure.getSuppressed()[0]);
    }

    @Test
    void blockedListenerRetainsOnlyBoundedNewestPendingEvents() throws Exception {
        CountDownLatch firstDeliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstDelivery = new CountDownLatch(1);
        CountDownLatch deliveries = new CountDownLatch(DiagnosticEmitter.MAX_PENDING_PER_DESTINATION + 1);
        CopyOnWriteArrayList<String> deliveredPids = new CopyOnWriteArrayList<>();
        DiagnosticEmitter emitter = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    deliveredPids.add(event.attributes().get("pid"));
                    deliveries.countDown();
                    if (firstDeliveryStarted.getCount() > 0) {
                        firstDeliveryStarted.countDown();
                        awaitIgnoringInterrupts(releaseFirstDelivery);
                    }
                }),
                "run",
                CommandEcho.empty());

        emitter.emit(DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", "-1"));
        assertTrue(firstDeliveryStarted.await(1, TimeUnit.SECONDS));
        for (int event = 0; event < 100; event++) {
            emitter.emit(
                    DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", Integer.toString(event)));
        }
        releaseFirstDelivery.countDown();

        assertTrue(deliveries.await(2, TimeUnit.SECONDS));
        assertEquals(DiagnosticEmitter.MAX_PENDING_PER_DESTINATION + 1, deliveredPids.size());
        assertEquals("-1", deliveredPids.get(0));
        assertEquals("99", deliveredPids.get(deliveredPids.size() - 1));
    }

    @Test
    void diagnosticCallbacksNeverReuseThreadsAcrossEmitterOwnership() throws Exception {
        ThreadLocal<String> contamination = new ThreadLocal<>();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        CopyOnWriteArrayList<Thread> callbackThreads = new CopyOnWriteArrayList<>();
        AtomicReference<String> contaminationFailure = new AtomicReference<>();
        try {
            for (int invocation = 0; invocation < 6; invocation++) {
                CountDownLatch delivered = new CountDownLatch(1);
                DiagnosticEmitter emitter = DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(event -> {
                            if (contamination.get() != null) {
                                contaminationFailure.compareAndSet(null, "diagnostic ThreadLocal crossed emitters");
                            }
                            if (inherited.get() != null) {
                                contaminationFailure.compareAndSet(
                                        null, "diagnostic callback inherited submitting-thread state");
                            }
                            contamination.set("must-die-with-thread");
                            callbackThreads.add(Thread.currentThread());
                            delivered.countDown();
                        }),
                        "run",
                        CommandEcho.empty());

                emitter.emit(DiagnosticEventType.COMMAND_PREPARED);
                assertTrue(delivered.await(1, TimeUnit.SECONDS));
            }
        } finally {
            inherited.remove();
        }
        assertSame(null, contaminationFailure.get());
        assertEquals(6, Set.copyOf(callbackThreads).size());
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

    private static final class FailureNamespaceSegmentWhoseNameIsDeliberatelyLongOne {

        private static final class FailureNamespaceSegmentWhoseNameIsDeliberatelyLongTwo {

            private static final class FailureNamespaceSegmentWhoseNameIsDeliberatelyLongThree {

                private static final class ExcessivelyNamedFailure extends RuntimeException {

                    private static final long serialVersionUID = 1L;
                }
            }
        }
    }
}
