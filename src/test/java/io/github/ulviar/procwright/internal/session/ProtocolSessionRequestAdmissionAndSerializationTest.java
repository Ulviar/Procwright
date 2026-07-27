/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolSessionRequestAdmissionAndSerializationTest extends ProtocolSessionContractSupport {

    @Test
    void abandonedProtocolFailureDoesNotChangeTimeout() throws Exception {
        for (Throwable lateFailure : List.of(
                new IllegalStateException("late protocol runtime failure"),
                new AssertionError("late protocol error"))) {
            assertAbandonedProtocolFailureIsIsolated(lateFailure);
        }
    }

    private static void assertAbandonedProtocolFailureIsIsolated(Throwable lateFailure) throws Exception {
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                awaitUninterruptibly(releaseDecoder);
                if (lateFailure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw (Error) lateFailure;
            }
        };
        DefaultProtocolSession<String, String> protocol = protocolSession(
                new ControllableProcess(
                        OutputStream.nullOutputStream(),
                        new BlockingUntilClosedInputStream(),
                        InputStream.nullInputStream()),
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofMillis(40)));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = caller.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));

            ProtocolSessionException timeout =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());

            releaseDecoder.countDown();
            ProtocolSessionException persisted =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("after-timeout"));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, persisted.reason());
        } finally {
            releaseDecoder.countDown();
            protocol.close();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void callerInterruptBeforeDeadlineHasCanonicalFailureAndRestoresCallerStatus() throws Exception {
        BlockingUntilClosedInputStream stdout = new BlockingUntilClosedInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        AtomicReference<ProtocolSessionException> decoderFailure = new AtomicReference<>();
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch decoderFailureObserved = new CountDownLatch(1);
        IllegalStateException callbackFailure = new IllegalStateException("decoder failed after cancellation");
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                try {
                    return readers.stdout().readByte();
                } catch (ProtocolSessionException failure) {
                    decoderFailure.set(failure);
                    decoderFailureObserved.countDown();
                    throw callbackFailure;
                }
            }
        };
        DefaultProtocolSession<String, Byte> protocol = protocolSession(
                process, adapter, ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofMinutes(1)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request("ignored"));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));
            callerThread.get().interrupt();

            ProtocolSessionException interrupted =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.FAILURE, interrupted.reason());
            assertEquals("Interrupted while decoding protocol response", interrupted.getMessage());
            assertInstanceOf(InterruptedException.class, interrupted.getCause());
            assertTrue(callerInterruptRestored.get());
            assertTrue(decoderFailureObserved.await(1, TimeUnit.SECONDS));
            assertSame(interrupted, decoderFailure.get());
            assertEquals(0, interrupted.getSuppressed().length);
            assertEquals(0, callbackFailure.getSuppressed().length);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertSame(interrupted, followUp.getCause());
        } finally {
            protocol.close();
            stdout.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void callerInterruptDuringWriterSelectsCanonicalFailureBeforeCallbackFailure() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        IllegalStateException callbackFailure = new IllegalStateException("writer failed after interruption");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writerStarted.countDown();
                try {
                    releaseWriter.await();
                } catch (InterruptedException interruption) {
                    callbackInterrupted.countDown();
                    throw callbackFailure;
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
        ControllableProcess process = new ControllableProcess();
        DefaultProtocolSession<String, String> protocol = protocolSession(
                process, adapter, ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)));
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request("request"));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(writerStarted.await(1, TimeUnit.SECONDS));

            callerThread.get().interrupt();
            ProtocolSessionException interrupted =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.FAILURE, interrupted.reason());
            assertEquals("Interrupted while writing protocol request", interrupted.getMessage());
            assertInstanceOf(InterruptedException.class, interrupted.getCause());
            assertTrue(callerInterruptRestored.get());
            assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
            assertEquals(0, interrupted.getSuppressed().length);
            assertEquals(0, callbackFailure.getSuppressed().length);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("retry"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertSame(interrupted, followUp.getCause());
        } finally {
            releaseWriter.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void serializedWaiterTimeoutBeforeAdmissionLeavesSessionReusableAndWritesNothing() throws Exception {
        assertRecoverableSerializedWaiterFailure(false);
    }

    @Test
    void serializedWaiterInterruptionBeforeAdmissionLeavesSessionReusableAndRestoresInterrupt() throws Exception {
        assertRecoverableSerializedWaiterFailure(true);
    }

    private static void assertRecoverableSerializedWaiterFailure(boolean interrupt) throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        CountDownLatch firstResponseStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstResponse = new CountDownLatch(1);
        AtomicInteger responses = new AtomicInteger();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                if (responses.getAndIncrement() == 0) {
                    firstResponseStarted.countDown();
                    awaitUninterruptibly(releaseFirstResponse);
                }
                return "response";
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        ControlledRequestLockWaiter lockWaiter = new ControlledRequestLockWaiter();
        DefaultProtocolSession<String, String> protocol = protocolSession(
                process,
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)),
                ProtocolSessionTestDependencies.withRequestLockWaiter(lockWaiter));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        AtomicBoolean waiterInterruptRestored = new AtomicBoolean();
        try {
            Future<String> active = executor.submit(() -> protocol.request("active"));
            assertTrue(firstResponseStarted.await(1, TimeUnit.SECONDS));
            Future<Throwable> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request("not-admitted"));
                waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(lockWaiter.awaitContended(), "second request did not enter the serialization wait");

            if (interrupt) {
                Objects.requireNonNull(waiterThread.get(), "waiterThread").interrupt();
            } else {
                lockWaiter.expire();
            }
            ProtocolSessionException failure =
                    assertInstanceOf(ProtocolSessionException.class, waiter.get(2, TimeUnit.SECONDS));

            assertEquals(
                    interrupt ? ProtocolSessionException.Reason.FAILURE : ProtocolSessionException.Reason.TIMEOUT,
                    failure.reason());
            if (interrupt) {
                assertEquals("Interrupted while waiting to start protocol request", failure.getMessage());
                assertInstanceOf(InterruptedException.class, failure.getCause());
            } else {
                assertEquals("Protocol request timed out", failure.getMessage());
                assertNull(failure.getCause());
            }
            assertEquals(interrupt, waiterInterruptRestored.get());
            assertTrue(process.isAlive());
            assertFalse(protocol.onExit().isDone());
            assertEquals("active\n", stdin.toString(StandardCharsets.UTF_8));

            releaseFirstResponse.countDown();
            assertEquals("response", active.get(2, TimeUnit.SECONDS));
            assertEquals("response", protocol.request("retry"));
            assertEquals("active\nretry\n", stdin.toString(StandardCharsets.UTF_8));
            assertTrue(process.isAlive());
            assertFalse(protocol.onExit().isDone());
        } finally {
            releaseFirstResponse.countDown();
            lockWaiter.expire();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void activeWriterCancellationSelectsClosedBeforeCallbackFailure() throws Exception {
        assertActiveCallbackCancellation(true);
    }

    @Test
    void activeDecoderCancellationSelectsClosedBeforeCallbackFailure() throws Exception {
        assertActiveCallbackCancellation(false);
    }

    private static void assertActiveCallbackCancellation(boolean writerPhase) throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        IllegalStateException callbackFailure =
                new IllegalStateException((writerPhase ? "writer" : "decoder") + " failed after cancellation");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if (writerPhase) {
                    awaitCancellation();
                } else {
                    writer.flush();
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                if (!writerPhase) {
                    awaitCancellation();
                }
                return "unused";
            }

            private void awaitCancellation() {
                callbackStarted.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException interruption) {
                    callbackInterrupted.countDown();
                    throw callbackFailure;
                }
            }
        };
        ControllableProcess process = new ControllableProcess();
        DefaultProtocolSession<String, String> protocol = protocolSession(
                process, adapter, ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofDays(1)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            protocol.close();
            ProtocolSessionException closed =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.CLOSED, closed.reason());
            assertInstanceOf(TimedTaskRunner.TaskCancelledException.class, closed.getCause());
            assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
            assertEquals(0, closed.getSuppressed().length);
            assertEquals(0, callbackFailure.getSuppressed().length);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("retry"));
            assertEquals(ProtocolSessionException.Reason.CLOSED, followUp.reason());
        } finally {
            releaseCallback.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static final class ControlledRequestLockWaiter implements SerializedRequestGate.Waiter {

        private final CountDownLatch contended = new CountDownLatch(1);
        private final CountDownLatch expired = new CountDownLatch(1);

        @Override
        public boolean acquire(java.util.concurrent.locks.ReentrantLock lock, long remainingNanos)
                throws InterruptedException {
            if (lock.tryLock()) {
                return true;
            }
            contended.countDown();
            expired.await();
            return false;
        }

        private boolean awaitContended() throws InterruptedException {
            return contended.await(1, TimeUnit.SECONDS);
        }

        private void expire() {
            expired.countDown();
        }
    }
}
