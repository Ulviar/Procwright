/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamExit;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultStreamSessionTerminalClaimTest extends DefaultStreamSessionTestSupport {

    @Test
    void truncationDiagnosticFailureDoesNotAlterStreamingOutcome() throws Exception {
        byte[] output = "x".repeat(2048).getBytes(StandardCharsets.UTF_8);
        ControllableProcess process =
                new ControllableProcess(new ByteArrayInputStream(output), InputStream.nullInputStream());
        AtomicInteger deliveredChars = new AtomicInteger();
        DiagnosticEmitter diagnostics = DiagnosticEmitterTestSupport.failOnceOn(
                DiagnosticsSettings.disabled().withListener(event -> {}),
                "stream-diagnostic-failure-test",
                DiagnosticEventType.OUTPUT_TRUNCATED,
                new AssertionError("OUTPUT_TRUNCATED failed"));
        DefaultStreamSession stream = openStream(
                process, plan(chunk -> deliveredChars.addAndGet(chunk.text().length())), diagnostics);

        try {
            process.complete(0);
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            assertEquals(0, result.exitCode().orElseThrow());
            assertEquals(output.length, deliveredChars.get());
        } finally {
            stream.close();
        }
    }

    @Test
    void normalCompletionClaimRejectsALateTimeoutWithoutPostTerminalDiagnostics() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = openStream(
                process,
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            process.complete(0);
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            stream.expireTimeout();

            assertFalse(result.timedOut());
            assertFalse(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
        } finally {
            stream.close();
        }
    }

    @Test
    void timeoutClaimWinsBeforeProcessCompletionAndPublishesOneConsistentOutcome() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = openStream(
                process,
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            stream.expireTimeout();
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            assertTrue(result.timedOut());
            assertFalse(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(1, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(1, timeoutShutdownCount(events));
        } finally {
            stream.close();
        }
    }

    @Test
    void closeClaimRejectsLateTimeoutAndNormalCompletion() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = openStream(
                process,
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            stream.close();
            stream.expireTimeout();
            process.complete(0);
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            assertFalse(result.timedOut());
            assertTrue(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
            assertEquals(1, shutdownCount(events, "close"));
        } finally {
            stream.close();
        }
    }

    @Test
    void failureClaimRejectsLateTimeoutAndNormalCompletion() throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream("x");
        ControllableProcess process = new ControllableProcess(stdout, InputStream.nullInputStream());
        IllegalStateException listenerFailure = new IllegalStateException("listener failed");
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = openStream(
                process,
                plan(chunk -> {
                    throw listenerFailure;
                }),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            assertTrue(stdout.awaitReadStarted());
            stdout.release();
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> stream.onExit().get(1, TimeUnit.SECONDS));
            StreamException streamFailure = (StreamException) failure.getCause();
            assertSame(listenerFailure, streamFailure.getCause());

            stream.expireTimeout();
            process.complete(0);

            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
            assertEquals(1, shutdownCount(events, "failure"));
        } finally {
            stdout.release();
            stream.close();
        }
    }

    private static int count(List<DiagnosticEvent> events, DiagnosticEventType type) {
        return Math.toIntExact(
                events.stream().filter(event -> event.type() == type).count());
    }

    private static int timeoutShutdownCount(List<DiagnosticEvent> events) {
        return shutdownCount(events, "timeout");
    }

    private static int shutdownCount(List<DiagnosticEvent> events, String reason) {
        return Math.toIntExact(events.stream()
                .filter(event -> event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED)
                .filter(event -> reason.equals(event.attributes().get("reason")))
                .count());
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
}
