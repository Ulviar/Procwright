package io.github.ulviar.icli.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.icli.command.OutputMode;
import io.github.ulviar.icli.internal.DiagnosticAttributeSchema;
import io.github.ulviar.icli.internal.DiagnosticEmitter;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        attributes.put("timedOut", "false");
        attributes.put("exitCode", "0");

        DiagnosticEvent event = new DiagnosticEvent(
                DiagnosticEventType.PROCESS_EXITED, Instant.EPOCH, "run", CommandEcho.empty(), attributes);

        attributes.put("exitCode", "99");

        assertEquals("0", event.attributes().get("exitCode"));
        assertEquals(Map.of("timedOut", "false", "exitCode", "0"), event.attributes());
    }

    @Test
    void listenerFailureDoesNotEscapeEmitter() {
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                DiagnosticsOptions.defaults().withListener(event -> {
                    throw new IllegalStateException("ignored");
                }),
                "run",
                CommandEcho.empty());

        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
    }

    @Test
    void diagnosticsCallbacksAreBestEffortAsync() {
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                DiagnosticsOptions.defaults().withListener(event -> sleep(Duration.ofMillis(300))),
                "run",
                CommandEcho.empty());

        long started = System.nanoTime();
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofMillis(100)) < 0);
    }

    @Test
    void defaultDiagnosticsUseDisabledFastPath() {
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(DiagnosticsOptions.defaults(), "run", CommandEcho.empty());

        assertFalse(diagnostics.enabled());
    }

    @Test
    void disabledDiagnosticsDoNotBuildLazyCommandEcho() {
        AtomicInteger echoBuilds = new AtomicInteger();
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(DiagnosticsOptions.defaults(), "run", () -> {
            echoBuilds.incrementAndGet();
            return CommandEcho.empty();
        });

        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);

        assertEquals(0, echoBuilds.get());
    }

    @Test
    void disabledDiagnosticsStillValidateScenario() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticEmitter.of(DiagnosticsOptions.defaults(), " ", () -> CommandEcho.empty()));
    }

    @Test
    void customDiagnosticsRequireEventDelivery() {
        assertTrue(DiagnosticEmitter.of(
                        DiagnosticsOptions.defaults().withListener(event -> {}), "run", CommandEcho.empty())
                .enabled());
        assertTrue(DiagnosticEmitter.of(
                        DiagnosticsOptions.defaults().withTranscriptSink(event -> {}), "run", CommandEcho.empty())
                .enabled());
    }

    @Test
    void diagnosticsCallbackThrowablesAreContained() {
        AtomicInteger uncaught = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(2);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> uncaught.incrementAndGet());
        try {
            DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
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

    @Test
    void transcriptSinkReceivesEventWhenListenerBlocks() throws Exception {
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch sinkDelivered = new CountDownLatch(1);
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                new DiagnosticsOptions(
                        event -> {
                            listenerStarted.countDown();
                            await(releaseListener);
                        },
                        event -> sinkDelivered.countDown()),
                "run",
                CommandEcho.empty());

        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);

        assertTrue(listenerStarted.await(1, TimeUnit.SECONDS));
        try {
            assertTrue(sinkDelivered.await(200, TimeUnit.MILLISECONDS));
        } finally {
            releaseListener.countDown();
        }
    }

    @Test
    void eventsFromOneDiagnosticsEmitterShareCorrelationId() throws Exception {
        List<DiagnosticEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delivered = new CountDownLatch(2);
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                DiagnosticsOptions.defaults().withListener(event -> {
                    events.add(event);
                    delivered.countDown();
                }),
                "run",
                CommandEcho.empty());

        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        diagnostics.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(1, events.stream().map(DiagnosticEvent::runId).distinct().count());
        assertFalse(events.get(0).runId().isBlank());
    }

    @Test
    void separateDiagnosticsEmittersUseSeparateCorrelationIds() throws Exception {
        List<DiagnosticEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delivered = new CountDownLatch(2);
        DiagnosticsOptions options = DiagnosticsOptions.defaults().withListener(event -> {
            events.add(event);
            delivered.countDown();
        });

        DiagnosticEmitter.of(options, "run", CommandEcho.empty()).emit(DiagnosticEventType.COMMAND_PREPARED);
        DiagnosticEmitter.of(options, "run", CommandEcho.empty()).emit(DiagnosticEventType.COMMAND_PREPARED);

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertNotEquals(events.get(0).runId(), events.get(1).runId());
    }

    @Test
    void diagnosticsMarkdownSchemaMatchesProductionSchema() throws Exception {
        assertEquals(
                DiagnosticAttributeSchema.allowedAttributesByType(),
                parseDiagnosticsMarkdownSchema(Path.of("context/diagnostics.md")));
    }

    @Test
    void diagnosticsContractsDocumentBestEffortUnorderedDelivery() throws Exception {
        String context = Files.readString(Path.of("context/diagnostics.md"), StandardCharsets.UTF_8);
        String publicDocs = Files.readString(Path.of("docs/reference/diagnostics.md"), StandardCharsets.UTF_8);

        assertTrue(context.contains("не гарантирует ordering"));
        assertTrue(publicDocs.contains("ordering between callback deliveries is not a cross-thread contract"));
    }

    @Test
    void diagnosticAttributeSchemaRejectsInvalidShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticAttributeSchema.validate(
                        DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("exitCode", "0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticAttributeSchema.validate(
                        DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", "not-a-number")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticAttributeSchema.validate(
                        DiagnosticEventType.OUTPUT_TRUNCATED,
                        Map.of("source", "stdout", "limitBytes", "8", "limitChars", "8")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticAttributeSchema.validate(
                        DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "user")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticAttributeSchema.validate(
                        DiagnosticEventType.PROCESS_FAILED,
                        DiagnosticEmitter.attributes("error", "secret failure text")));
    }

    private static Map<DiagnosticEventType, Set<String>> parseDiagnosticsMarkdownSchema(Path path) throws Exception {
        EnumMap<DiagnosticEventType, Set<String>> schema = new EnumMap<>(DiagnosticEventType.class);
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.startsWith("| `")) {
                continue;
            }
            String[] columns = line.split("\\|");
            if (columns.length < 4) {
                continue;
            }
            DiagnosticEventType type =
                    DiagnosticEventType.valueOf(columns[1].trim().replace("`", ""));
            schema.put(type, attributes(columns[2].trim()));
        }
        return Map.copyOf(schema);
    }

    private static Set<String> attributes(String markdownCell) {
        if (markdownCell.equals("none")) {
            return Set.of();
        }
        LinkedHashSet<String> attributes = new LinkedHashSet<>();
        Arrays.stream(markdownCell.split(",| or "))
                .map(value -> value.trim().replace("optional ", "").replace("`", ""))
                .filter(value -> !value.isBlank())
                .forEach(attributes::add);
        return Set.copyOf(attributes);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        }
    }

    private static void sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        }
    }
}
