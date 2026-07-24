/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LineSessionSerializationAndDeadlinesIntegrationTest extends LineSessionDeadlineIntegrationSupport {

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
            session.onExit().get(2, TimeUnit.SECONDS);
            assertTrue(session.onExit().isDone());
            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.TIMEOUT, followUp.reason());
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
            assertEquals(LineSessionException.Reason.FAILURE, ((LineSessionException) thrown.get()).reason());
            assertTrue(interruptedAfterCatch.get(), "caller interrupt status must be restored after the typed failure");
            session.onExit().get(2, TimeUnit.SECONDS);
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
    void nonCooperativeRequestEncodingCannotBlockCallerPastDeadline() throws Exception {
        BlockingUtf8Charset charset = new BlockingUtf8Charset();
        LineSessionScenario.Draft service = fixtureScenario().withCharset(charset);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            long started = System.nanoTime();
            try {
                Future<Throwable> request =
                        executor.submit(() -> captureFailure(() -> session.request("first", Duration.ofMillis(50))));
                assertTrue(charset.awaitEncoderStarted());

                Throwable failure = request.get(500, TimeUnit.MILLISECONDS);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertTrue(failure instanceof LineSessionException);
                assertEquals(LineSessionException.Reason.TIMEOUT, ((LineSessionException) failure).reason());
                assertTrue(elapsed.compareTo(Duration.ofMillis(400)) < 0, () -> "encoding timeout took " + elapsed);
            } finally {
                charset.releaseEncoder();
                assertTrue(charset.awaitEncoderFinished());
                assertTrue(charset.awaitEncoderTaskStopped());
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
            assertFalse(session.onExit().isDone());
            assertEquals(
                    "response:second",
                    session.request("second", Duration.ofSeconds(1)).text());
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

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            long started = System.nanoTime();
            LineSessionException timeout =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofMillis(500)));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertTrue(elapsed.compareTo(Duration.ofMillis(1500)) < 0, () -> "decoder timeout took " + elapsed);
            assertEquals(0, decoderStarted.getCount());
        } finally {
            releaseDecoder.countDown();
            assertTrue(decoderFinished.await(1, TimeUnit.SECONDS));
            assertTaskStopped(decoderThread.get(), "line response decoder");
        }
    }
}
