/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.StdoutLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TwoLineTextAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.captureFailure;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolRequestAdmissionAndDeadlineIntegrationTest {

    @Test
    void protocolRequestTimeoutClosesProcessAndPreservesTerminalReason() throws Exception {
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new StdoutLineAdapter(16),
                call -> call.withArgs("ignore-stdin", "--millis=5000", "--started=false"));
        try {
            ProtocolSessionException timeout =
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofMillis(100)));

            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            assertExitFailedWith(session, timeout);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        } finally {
            session.close();
        }
    }

    @Test
    void parallelRequestsAreSerialized() throws Exception {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new TwoLineTextAdapter(),
                call -> call.withArgs("two-line-delay-repl", "--delay-millis=100"))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<String> first = executor.submit(() -> session.request("a", Duration.ofSeconds(5)));
                Future<String> second = executor.submit(() -> session.request("b", Duration.ofSeconds(5)));

                List<String> responses = List.of(first.get(), second.get());

                assertTrue(responses.contains("start:a\nend:a"), () -> "interleaved response: " + responses);
                assertTrue(responses.contains("start:b\nend:b"), () -> "interleaved response: " + responses);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolTimeoutIncludesWaitingForSerializedAccess() throws Exception {
        CountDownLatch firstResponseStarted = new CountDownLatch(1);
        CoordinatedTwoLineAdapter adapter = new CoordinatedTwoLineAdapter(firstResponseStarted);

        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), adapter, call -> call.withArgs("two-line-delay-repl", "--delay-millis=300"))) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<String> first = executor.submit(() -> session.request("first", Duration.ofSeconds(2)));
                assertTrue(firstResponseStarted.await(1, TimeUnit.SECONDS));

                Future<Throwable> queued =
                        executor.submit(() -> captureFailure(() -> session.request("queued", Duration.ofMillis(50))));
                ProtocolSessionException timeout =
                        assertInstanceOf(ProtocolSessionException.class, queued.get(1, TimeUnit.SECONDS));

                assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
                assertEquals("start:first\nend:first", first.get());
                assertEquals(List.of("first"), adapter.writtenRequests());
                assertEquals("start:after\nend:after", session.request("after", Duration.ofSeconds(2)));
                assertEquals(List.of("first", "after"), adapter.writtenRequests());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolAdapterCannotReturnSuccessAfterRequestDeadline() throws Exception {
        CountDownLatch responseRead = new CountDownLatch(1);
        CountDownLatch releaseAdapter = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new SlowAfterReadAdapter(responseRead, releaseAdapter), call -> call.withArgs(
                                "controlled-line-repl")
                        .withShutdown(
                                ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofSeconds(1))))) {
            Future<Throwable> request =
                    executor.submit(() -> captureFailure(() -> session.request("hello", Duration.ofMillis(500))));
            assertTrue(responseRead.await(2, TimeUnit.SECONDS));
            ProtocolSessionException timeout =
                    assertInstanceOf(ProtocolSessionException.class, request.get(10, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            assertExitFailedWith(session, timeout);
        } finally {
            releaseAdapter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nonCooperativeProtocolDecoderCannotBlockCallerPastRequestDeadline() throws Exception {
        CountDownLatch decoderStarted = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        CountDownLatch decoderFinished = new CountDownLatch(1);
        AtomicReference<Thread> decoderThread = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new TextLineAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                decoderThread.set(Thread.currentThread());
                try {
                    String response = super.readResponse(readers);
                    decoderStarted.countDown();
                    awaitIgnoringInterrupts(releaseDecoder);
                    return response;
                } finally {
                    decoderFinished.countDown();
                }
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl")
                        .withShutdown(
                                ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofSeconds(1))))) {
            Future<Throwable> request =
                    executor.submit(() -> captureFailure(() -> session.request("hello", Duration.ofMillis(500))));
            ProtocolSessionException timeout =
                    assertInstanceOf(ProtocolSessionException.class, request.get(10, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            assertEquals(0, decoderStarted.getCount());
            assertEquals(1, decoderFinished.getCount());
            assertExitFailedWith(session, timeout);
            assertEquals(1, decoderFinished.getCount());
        } finally {
            releaseDecoder.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(decoderFinished.await(1, TimeUnit.SECONDS));
            assertTaskStopped(decoderThread.get(), "protocol response decoder");
        }
    }

    @Test
    void nonCooperativeProtocolWriterCannotBlockCallerPastRequestDeadline() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new TextLineAdapter() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writerThread.set(Thread.currentThread());
                writerStarted.countDown();
                try {
                    awaitIgnoringInterrupts(releaseWriter);
                    super.writeRequest(request, writer);
                } finally {
                    writerFinished.countDown();
                }
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl")
                        .withShutdown(
                                ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofSeconds(1))))) {
            Future<Throwable> request =
                    executor.submit(() -> captureFailure(() -> session.request("hello", Duration.ofMillis(500))));
            ProtocolSessionException timeout =
                    assertInstanceOf(ProtocolSessionException.class, request.get(10, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            assertEquals(0, writerStarted.getCount());
            assertEquals(1, writerFinished.getCount());
            assertExitFailedWith(session, timeout);
            assertEquals(1, writerFinished.getCount());
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(writerFinished.await(1, TimeUnit.SECONDS));
            assertTaskStopped(writerThread.get(), "protocol request writer");
        }
    }

    private static void assertTaskStopped(Thread thread, String task) throws InterruptedException {
        assertTrue(thread != null, task + " thread was not captured");
        thread.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(thread.isAlive(), task + " callback did not finish");
    }

    private static void assertExitFailedWith(ProtocolSession<?, ?> session, ProtocolSessionException requestFailure) {
        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
        ProtocolSessionException exitFailure = assertInstanceOf(ProtocolSessionException.class, observed.getCause());
        assertEquals(requestFailure.reason(), exitFailure.reason());
        assertSame(requestFailure, exitFailure);
    }

    private static final class CoordinatedTwoLineAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch firstResponseStarted;
        private final List<String> writtenRequests = new ArrayList<>();

        private CoordinatedTwoLineAdapter(CountDownLatch firstResponseStarted) {
            this.firstResponseStarted = firstResponseStarted;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writtenRequests.add(request);
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            String first = stdout.readLine(32);
            firstResponseStarted.countDown();
            return first + "\n" + stdout.readLine(32);
        }

        private List<String> writtenRequests() {
            return List.copyOf(writtenRequests);
        }
    }

    private static final class SlowAfterReadAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch responseRead;
        private final CountDownLatch release;

        private SlowAfterReadAdapter(CountDownLatch responseRead, CountDownLatch release) {
            this.responseRead = responseRead;
            this.release = release;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            String response = readers.stdout().readLine(64);
            responseRead.countDown();
            awaitIgnoringInterrupts(release);
            return response;
        }
    }
}
