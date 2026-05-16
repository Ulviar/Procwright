package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DiagnosticsOptionsTest {

    @Test
    void commandEchoOmitsEnvironmentValues() {
        CommandEcho echo = new CommandEcho(
                "tool",
                2,
                java.util.Optional.of(Path.of("project")),
                List.of("SECRET_TOKEN"),
                OutputMode.SEPARATE,
                TerminalPolicy.DISABLED);

        assertEquals(List.of("SECRET_TOKEN"), echo.environmentNames());
        assertEquals(2, echo.argumentCount());
        assertTrue(echo.toString().contains("SECRET_TOKEN"));
        assertFalse(echo.toString().contains("secret-argument"));
        assertFalse(echo.toString().contains("secret-value"));
    }

    @Test
    void diagnosticEventSnapshotsAttributes() {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("exitCode", "0");

        DiagnosticEvent event = new DiagnosticEvent(
                DiagnosticEventType.PROCESS_EXITED, Instant.EPOCH, "run", CommandEcho.empty(), attributes);

        attributes.put("exitCode", "99");

        assertEquals("0", event.attributes().get("exitCode"));
        assertEquals(Map.of("exitCode", "0"), event.attributes());
    }

    @Test
    void listenerFailureDoesNotEscapeEmitter() {
        Diagnostics diagnostics = Diagnostics.of(
                DiagnosticsOptions.defaults().withListener(event -> {
                    throw new IllegalStateException("ignored");
                }),
                "run",
                CommandEcho.empty());

        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
    }

    @Test
    void diagnosticsCallbacksAreBestEffortAsync() {
        Diagnostics diagnostics = Diagnostics.of(
                DiagnosticsOptions.defaults().withListener(event -> sleep(Duration.ofMillis(300))),
                "run",
                CommandEcho.empty());

        long started = System.nanoTime();
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofMillis(100)) < 0);
    }

    @Test
    void diagnosticsCallbackThrowablesAreContained() {
        AtomicInteger uncaught = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(2);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> uncaught.incrementAndGet());
        try {
            Diagnostics diagnostics = Diagnostics.of(
                    new DiagnosticsOptions(
                            event -> {
                                delivered.countDown();
                                throw new AssertionError("listener failure");
                            },
                            event -> {
                                delivered.countDown();
                                throw new AssertionError("sink failure");
                            }),
                    "run",
                    CommandEcho.empty());

            diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
            assertTrue(delivered.await(1, TimeUnit.SECONDS));

            assertEquals(0, uncaught.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        }
    }
}
