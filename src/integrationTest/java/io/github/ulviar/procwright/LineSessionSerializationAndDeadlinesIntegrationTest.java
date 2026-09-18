/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.openLineSession;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.sleep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LineSessionSerializationAndDeadlinesIntegrationTest {

    @Test
    void timeoutAfterRequestWriteClosesSessionAndPreservesTypedFailure() throws Exception {
        ResponseDecoder waitForDone = reader -> {
            while (true) {
                String line = reader.readLine();
                if (line.equals("done")) {
                    return List.of(line);
                }
            }
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(waitForDone);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("slow", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.transcript().text().contains("stdout: started:slow"));
            assertExitFailedWith(session, exception);
            assertTrue(session.onExit().isDone());
            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.TIMEOUT, followUp.reason());
            assertSame(exception, followUp.getCause());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        }
    }

    @Test
    void callerInterruptDuringRequestIsTypedFailureAndRestoresInterruptStatus() throws Exception {
        try (LineSession session = openLineSession(
                fixtureScenario(), call -> call.withArgs("controlled-line-repl", "--slow-response-millis=60000"))) {
            CountDownLatch requestStarted = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean interruptedAfterCatch = new AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    requestStarted.countDown();
                    session.request("slow-response", Duration.ofSeconds(30));
                } catch (Throwable throwable) {
                    thrown.set(throwable);
                    interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
                }
            });

            caller.start();
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            assertTrue(
                    eventuallyTranscriptContains(session, "request-started:slow-response"),
                    "worker must receive the request before caller interruption");
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(caller.isAlive(), "interrupted caller must not stay blocked in request()");
            assertTrue(
                    thrown.get() instanceof LineSessionException,
                    () -> "expected typed line-session failure, got " + thrown.get());
            LineSessionException requestFailure = (LineSessionException) thrown.get();
            assertEquals(LineSessionException.Reason.FAILURE, requestFailure.reason());
            assertTrue(interruptedAfterCatch.get(), "caller interrupt status must be restored after the typed failure");
            assertExitFailedWith(session, requestFailure);
        }
    }

    @Test
    void parallelRequestsAreSerialized() throws Exception {
        ResponseDecoder twoLineDecoder = reader -> List.of(reader.readLine(), reader.readLine());
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(twoLineDecoder);

        try (LineSession session =
                openLineSession(service, call -> call.withArgs("two-line-delay-repl", "--delay-millis=100"))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<LineResponse> first = executor.submit(() -> session.request("a"));
                Future<LineResponse> second = executor.submit(() -> session.request("b"));

                List<String> firstLines = first.get().lines();
                List<String> secondLines = second.get().lines();
                List<List<String>> responses = List.of(firstLines, secondLines);

                assertTrue(responses.contains(List.of("start:a", "end:a")));
                assertTrue(responses.contains(List.of("start:b", "end:b")));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void requestTimeoutIncludesWaitingForSerializedAccess() throws Exception {
        CountDownLatch firstResponseStarted = new CountDownLatch(1);
        ResponseDecoder decoder = reader -> {
            String first = reader.readLine();
            firstResponseStarted.countDown();
            return List.of(first, reader.readLine());
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(decoder);

        try (LineSession session =
                openLineSession(service, call -> call.withArgs("two-line-delay-repl", "--delay-millis=300"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<LineResponse> first = executor.submit(() -> session.request("first", Duration.ofSeconds(2)));
                assertTrue(firstResponseStarted.await(1, TimeUnit.SECONDS));

                long started = System.nanoTime();
                LineSessionException timeout = assertThrows(
                        LineSessionException.class, () -> session.request("queued", Duration.ofMillis(50)));
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
                assertTrue(elapsed.compareTo(Duration.ofMillis(250)) < 0, () -> "queued timeout took " + elapsed);
                assertFalse(session.onExit().isDone());
                assertEquals(List.of("start:first", "end:first"), first.get().lines());
                assertEquals(
                        List.of("start:after", "end:after"),
                        session.request("after", Duration.ofSeconds(2)).lines());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void invalidRequestTimeoutIsRejectedBeforeEncoding() {
        CountingUtf8Charset charset = new CountingUtf8Charset(Duration.ZERO);
        LineSessionScenario.Draft service = fixtureScenario().withCharset(charset);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            assertThrows(IllegalArgumentException.class, () -> session.request("hello", Duration.ZERO));

            assertEquals(0, charset.encoderCreations());
        }
    }

    @Test
    void decoderCannotReturnSuccessAfterRequestDeadline() {
        ResponseDecoder decoder = reader -> {
            String line = reader.readLine();
            sleep(Duration.ofMillis(150));
            return List.of(line);
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(decoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException timeout =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofMillis(50)));

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertExitFailedWith(session, timeout);
        }
    }

    @Test
    void nonCooperativeDecoderCannotBlockCallerPastRequestDeadline() throws Exception {
        CountDownLatch decoderStarted = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        CountDownLatch decoderFinished = new CountDownLatch(1);
        AtomicReference<Thread> decoderThread = new AtomicReference<>();
        ResponseDecoder decoder = reader -> {
            decoderThread.set(Thread.currentThread());
            try {
                String line = reader.readLine();
                decoderStarted.countDown();
                awaitIgnoringInterrupts(releaseDecoder);
                return List.of(line);
            } finally {
                decoderFinished.countDown();
            }
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(decoder);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl")
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofSeconds(1))))) {
            Future<LineSessionException> request = executor.submit(() ->
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofMillis(500))));
            LineSessionException timeout = request.get(10, TimeUnit.SECONDS);

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertEquals(0, decoderStarted.getCount());
            assertEquals(1, decoderFinished.getCount());
            assertExitFailedWith(session, timeout);
            assertEquals(1, decoderFinished.getCount());
        } finally {
            releaseDecoder.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(decoderFinished.await(1, TimeUnit.SECONDS));
            assertTaskStopped(decoderThread.get(), "line response decoder");
        }
    }

    private static void assertExitFailedWith(LineSession session, LineSessionException selectedFailureSource) {
        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
        assertSame(selectedFailureSource, observed.getCause());
    }

    private static boolean eventuallyTranscriptContains(LineSession session, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (session.transcript().text().contains(expected)) {
                return true;
            }
            Thread.sleep(10);
        }
        return session.transcript().text().contains(expected);
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void assertTaskStopped(Thread thread, String task) throws InterruptedException {
        assertTrue(thread != null, task + " thread was not captured");
        thread.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(thread.isAlive(), task + " callback did not finish");
    }

    private static final class CountingUtf8Charset extends Charset {

        private final Duration firstEncoderDelay;
        private final AtomicBoolean delayPending = new AtomicBoolean(true);
        private final AtomicInteger encoderCreations = new AtomicInteger();

        private CountingUtf8Charset(Duration firstEncoderDelay) {
            super("X-Procwright-Line-Counting-UTF-8", new String[0]);
            this.firstEncoderDelay = firstEncoderDelay;
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations.incrementAndGet();
            if (delayPending.compareAndSet(true, false) && !firstEncoderDelay.isZero()) {
                sleep(firstEncoderDelay);
            }
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int encoderCreations() {
            return encoderCreations.get();
        }
    }
}
