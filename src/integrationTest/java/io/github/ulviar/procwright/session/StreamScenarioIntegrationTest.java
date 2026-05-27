package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.TestCliSupport;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class StreamScenarioIntegrationTest {

    @Test
    void listenDispatchesStdoutAndStderrChunksAndReportsExit() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        CommandService service = fixtureService(StreamOptions.defaults());

        try (StreamSession session = service.listen()
                .withArgs("exit", "--stdout=out", "--stderr=err")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertFalse(exit.timedOut());
            assertTrue(chunks.stream()
                    .anyMatch(chunk -> chunk.source() == StreamSource.STDOUT
                            && chunk.text().contains("out")));
            assertTrue(chunks.stream()
                    .anyMatch(chunk -> chunk.source() == StreamSource.STDERR
                            && chunk.text().contains("err")));
            assertTrue(exit.diagnostics().text().contains("stdout: out"));
            assertTrue(exit.diagnostics().text().contains("stderr: err"));
        }
    }

    @Test
    void listenClosesStdinOnStartByDefault() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        CommandService service = fixtureService(StreamOptions.defaults());

        try (StreamSession session = service.listen()
                .withArgs("stdin-echo", "--mode=bytes-count")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("bytes:0")));
        }
    }

    @Test
    void listenCanKeepStdinOpenUntilCallerClosesIt() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        CommandService service = fixtureService(StreamOptions.defaults());

        try (StreamSession session = service.listen()
                .withArgs("stdin-echo", "--mode=bytes-count")
                .withOpenStdin()
                .onOutput(chunks::add)
                .open()) {
            Thread.sleep(100);
            assertFalse(session.onExit().isDone());

            session.closeStdin();

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);
            assertEquals(0, exit.exitCode().orElseThrow());
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("bytes:0")));
        }
    }

    @Test
    void streamTimeoutStopsLongRunningProcess() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        CommandService service = fixtureService(StreamOptions.defaults().withTimeout(timeoutAfterFixtureStartup()));

        try (StreamSession session = service.listen()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(exitWaitTimeout().toSeconds(), TimeUnit.SECONDS);

            assertTrue(exit.timedOut());
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("started")));
        }
    }

    @Test
    void explicitCloseReportsClosedExit() throws Exception {
        CommandService service = fixtureService(StreamOptions.defaults());

        try (StreamSession session = service.listen()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .open()) {
            session.close();

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);
            assertTrue(exit.closed());
        }
    }

    @Test
    void listenerFailureCompletesExitExceptionallyWithDiagnostics() {
        CommandService service = fixtureService(StreamOptions.defaults());

        try (StreamSession session = service.listen()
                .configuredBy(call -> call.args("exit", "--stdout=boom").onOutput(chunk -> {
                    throw new IllegalStateException("listener failed");
                }))
                .open()) {
            ExecutionException exception = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamException = assertInstanceOf(StreamException.class, exception.getCause());

            assertTrue(streamException.diagnostics().text().contains("stdout: boom"));
        }
    }

    @Test
    void listenerCallbacksAreSerialized() throws Exception {
        AtomicBoolean insideListener = new AtomicBoolean();
        CopyOnWriteArrayList<String> chunks = new CopyOnWriteArrayList<>();
        CommandService service = fixtureService(StreamOptions.defaults());

        try (StreamSession session = service.listen()
                .configuredBy(call -> call.args(
                                "stream",
                                "--count=1",
                                "--stdout-template=out-start",
                                "--stderr-template=err-start",
                                "--delay-millis=100")
                        .onOutput(chunk -> {
                            assertFalse(insideListener.getAndSet(true));
                            try {
                                chunks.add(chunk.text());
                                Thread.sleep(50);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError("interrupted", exception);
                            } finally {
                                insideListener.set(false);
                            }
                        }))
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertTrue(chunks.stream().anyMatch(text -> text.contains("out-start")));
            assertTrue(chunks.stream().anyMatch(text -> text.contains("err-start")));
        }
    }

    @Test
    void callerCannotCompleteStreamExitFuture() throws Exception {
        CommandService service = fixtureService(StreamOptions.defaults());

        try (StreamSession session =
                service.listen().withArgs("sleep", "--millis=200").open()) {
            CompletableFuture<StreamExit> callerFuture = session.onExit();
            callerFuture.complete(new StreamExit(
                    java.util.OptionalInt.of(99), false, false, new StreamTranscript("fake", false), Duration.ZERO));

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);
            assertEquals(0, exit.exitCode().orElseThrow());
        }
    }

    @Test
    void boundedDiagnosticsDoNotStoreEntireOutput() throws Exception {
        CommandService service = fixtureService(StreamOptions.defaults().withDiagnosticLimit(64));

        try (StreamSession session = service.listen()
                .withArgs("burst", "--stdout-bytes=128", "--stdout-byte=x")
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.diagnostics().truncated());
            assertTrue(exit.diagnostics().text().length() <= 64);
        }
    }

    @Test
    void stderrIsDrainedWhileStreamingStdout() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        CommandService service = fixtureService(StreamOptions.defaults().withDiagnosticLimit(1024));

        try (StreamSession session = service.listen()
                .withArgs(
                        "burst",
                        "--stdout-first=false",
                        "--stdout-bytes=5",
                        "--stdout-byte=d",
                        "--stderr-bytes=256k",
                        "--stderr-byte=e")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertTrue(chunks.stream()
                    .anyMatch(chunk -> chunk.source() == StreamSource.STDOUT
                            && chunk.text().contains("ddddd")));
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.source() == StreamSource.STDERR));
        }
    }

    private static CommandService fixtureService(StreamOptions streamOptions) {
        return Procwright.command(TestCliSupport.command()).withStreamOptions(streamOptions);
    }

    private static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static Duration exitWaitTimeout() {
        return isWindows() ? Duration.ofSeconds(6) : Duration.ofSeconds(2);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
