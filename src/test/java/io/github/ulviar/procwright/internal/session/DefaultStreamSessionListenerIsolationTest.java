/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DefaultStreamSessionListenerIsolationTest extends DefaultStreamSessionTestSupport {

    @TestFactory
    Stream<DynamicTest> blockedListenerCannotDelayControlOutcomeOrLateFailureAccounting() {
        return Stream.of(ControlAction.values()).flatMap(control -> Stream.of(NestedFailureKind.values())
                .map(failureKind -> DynamicTest.dynamicTest(
                        control + " / late listener " + failureKind,
                        () -> assertBlockedListenerCannotDelayControlOutcome(control, failureKind))));
    }

    private static void assertBlockedListenerCannotDelayControlOutcome(
            ControlAction control, NestedFailureKind failureKind) throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream("chunk");
        CountingEofInputStream stderr = new CountingEofInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch listenerExited = new CountDownLatch(1);
        Throwable expected = failureKind.newFailure();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {
                    listenerEntered.countDown();
                    try {
                        awaitUninterruptibly(releaseListener);
                        throwUnchecked(expected);
                    } finally {
                        listenerExited.countDown();
                    }
                }),
                diagnostics());
        FutureTask<Throwable> controlTask = new FutureTask<>(() -> captureFailure(() -> control.terminate(stream)));
        Thread controlThread = new Thread(controlTask, "stream-blocked-listener-control");
        controlThread.setDaemon(true);
        try {
            assertTrue(stdout.awaitReadStarted());
            stdout.release();
            assertTrue(listenerEntered.await(1, TimeUnit.SECONDS));

            controlThread.start();

            assertSame(null, controlTask.get(1, TimeUnit.SECONDS));
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);
            assertEquals(control == ControlAction.CLOSE, result.closed());
            assertEquals(control == ControlAction.TIMEOUT, result.timedOut());
            assertTrue(eventually(() -> stdout.closeCalls() == 1 && stderr.closeCalls() == 1));

            releaseListener.countDown();
            assertTrue(listenerExited.await(1, TimeUnit.SECONDS));
            assertEquals(result, stream.onExit().get(1, TimeUnit.SECONDS));
        } finally {
            releaseListener.countDown();
            stdout.release();
            process.complete(143);
            stream.close();
            controlThread.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    void stdoutAndStderrCallbacksRemainSerializedOutsideTerminalOwnership() throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream("o");
        GatedChunkInputStream stderr = new GatedChunkInputStream("e");
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        CountDownLatch firstCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        CountDownLatch secondCallbackEntered = new CountDownLatch(1);
        CountDownLatch callbacksFinished = new CountDownLatch(2);
        AtomicInteger callbackEntries = new AtomicInteger();
        AtomicInteger activeCallbacks = new AtomicInteger();
        AtomicInteger maxActiveCallbacks = new AtomicInteger();
        AtomicReference<StreamSource> firstSource = new AtomicReference<>();
        AtomicReference<StreamSource> secondSource = new AtomicReference<>();

        DefaultSession rawSession = session(process);
        DefaultStreamSession stream = new DefaultStreamSession(
                rawSession,
                plan(chunk -> {
                    int active = activeCallbacks.incrementAndGet();
                    maxActiveCallbacks.accumulateAndGet(active, Math::max);
                    int entry = callbackEntries.incrementAndGet();
                    try {
                        if (entry == 1) {
                            firstSource.set(chunk.source());
                            firstCallbackEntered.countDown();
                            awaitUninterruptibly(releaseFirstCallback);
                        } else {
                            secondSource.set(chunk.source());
                            secondCallbackEntered.countDown();
                        }
                    } finally {
                        activeCallbacks.decrementAndGet();
                        callbacksFinished.countDown();
                    }
                }),
                diagnostics());

        try {
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            stdout.release();
            assertTrue(firstCallbackEntered.await(1, TimeUnit.SECONDS));
            assertEquals(StreamSource.STDOUT, firstSource.get());

            stderr.release();
            assertTrue(stderr.awaitChunkReturned());

            releaseFirstCallback.countDown();
            assertTrue(secondCallbackEntered.await(1, TimeUnit.SECONDS));
            assertTrue(callbacksFinished.await(1, TimeUnit.SECONDS));
            assertEquals(StreamSource.STDERR, secondSource.get());
            assertEquals(2, callbackEntries.get());
            assertEquals(0, activeCallbacks.get());
            assertEquals(1, maxActiveCallbacks.get());

            process.complete(0);
            StreamExit exit = stream.onExit().get(1, TimeUnit.SECONDS);
            assertEquals(0, exit.exitCode().orElseThrow());
        } finally {
            stdout.release();
            stderr.release();
            releaseFirstCallback.countDown();
            process.complete(0);
            stream.close();
        }
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
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

    private static final class GatedChunkInputStream extends InputStream {

        private final byte[] bytes;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch chunkReturned = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicReference<Thread> readerThread = new AtomicReference<>();

        private GatedChunkInputStream(String text) {
            this.bytes = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            readerThread.compareAndSet(null, Thread.currentThread());
            readStarted.countDown();
            awaitUninterruptibly(release);
            if (!delivered.compareAndSet(false, true)) {
                return -1;
            }
            int count = Math.min(length, bytes.length);
            System.arraycopy(bytes, 0, target, offset, count);
            chunkReturned.countDown();
            return count;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            release.countDown();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitChunkReturned() throws InterruptedException {
            return chunkReturned.await(1, TimeUnit.SECONDS);
        }

        private Thread readerThread() {
            return readerThread.get();
        }

        private void release() {
            release.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class CountingEofInputStream extends InputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private enum ControlAction {
        CLOSE,
        TIMEOUT;

        private void terminate(DefaultStreamSession stream) {
            switch (this) {
                case CLOSE -> stream.close();
                case TIMEOUT -> stream.expireTimeout();
            }
        }
    }

    private enum NestedFailureKind {
        RUNTIME,
        ERROR;

        private Throwable newFailure() {
            return switch (this) {
                case RUNTIME -> new IllegalStateException("liveness failed");
                case ERROR -> new AssertionError("liveness failed");
            };
        }
    }
}
