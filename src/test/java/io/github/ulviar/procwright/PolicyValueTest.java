package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.ExpectOptions;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledLineSessionOptions;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.PooledProtocolSessionOptions;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.session.SessionOptions;
import io.github.ulviar.procwright.session.StreamOptions;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PolicyValueTest {

    @Test
    void boundedCaptureRequiresPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> CapturePolicy.bounded(0));
        assertThrows(IllegalArgumentException.class, () -> CapturePolicy.bounded(-1));
    }

    @Test
    void boundedCaptureExposesByteLimit() {
        CapturePolicy.Bounded bounded = CapturePolicy.bounded(512);

        assertEquals(512, bounded.byteLimit());
    }

    @Test
    void shutdownPolicyRejectsNegativeGracePeriods() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShutdownPolicy.interruptThenKill(Duration.ofMillis(-1), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofMillis(-1)));
    }

    @Test
    void runOptionsRejectNegativeTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunOptions(
                        CapturePolicy.bounded(512),
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        Duration.ofMillis(-1),
                        StandardCharsets.UTF_8,
                        OutputMode.SEPARATE));
    }

    @Test
    void runOptionsExposeCharsetPolicy() {
        RunOptions options = new RunOptions(
                CapturePolicy.bounded(512),
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ofSeconds(1),
                CharsetPolicy.report(StandardCharsets.UTF_8),
                OutputMode.SEPARATE);

        assertEquals(StandardCharsets.UTF_8, options.charset());
        assertEquals(CharsetPolicy.report(StandardCharsets.UTF_8), options.charsetPolicy());
    }

    @Test
    void lineSessionOptionsRejectInvalidTimeoutAndTranscriptLimit() {
        assertThrows(IllegalArgumentException.class, () -> LineSessionOptions.defaults()
                .withRequestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> LineSessionOptions.defaults()
                .withTranscriptLimit(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionOptions.defaults()
                .withStdoutBacklogLimit(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionOptions.defaults()
                .withMaxLineChars(0));
    }

    @Test
    void expectOptionsRejectInvalidTimeoutAndTranscriptLimit() {
        assertThrows(
                IllegalArgumentException.class, () -> ExpectOptions.defaults().withTimeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class, () -> ExpectOptions.defaults().withTranscriptLimit(0));
        assertThrows(
                IllegalArgumentException.class, () -> ExpectOptions.defaults().withMatchBufferLimit(0));
    }

    @Test
    void terminalSizeRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(0, 24));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 0));
    }

    @Test
    void sessionOptionsCarryTerminalDefaults() {
        PtyProvider provider = PtyProvider.unavailable("no test provider");
        SessionOptions options = SessionOptions.defaults()
                .withTerminalPolicy(TerminalPolicy.REQUIRED)
                .withPtyProvider(provider)
                .withTerminalSize(new TerminalSize(100, 40));

        assertEquals(TerminalPolicy.REQUIRED, options.terminalPolicy());
        assertEquals(provider, options.ptyProvider());
        assertEquals(new TerminalSize(100, 40), options.terminalSize());
    }

    @Test
    void streamOptionsRejectInvalidDiagnosticLimitAndNegativeTimeout() {
        assertThrows(
                IllegalArgumentException.class, () -> StreamOptions.defaults().withDiagnosticLimit(0));
        assertThrows(
                IllegalArgumentException.class, () -> StreamOptions.defaults().withTimeout(Duration.ofMillis(-1)));
    }

    @Test
    void pooledLineSessionOptionsRejectInvalidPoolPolicies() {
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withMaxSize(0));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withWarmupSize(-1));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withMinIdle(-1));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withWarmupSize(2));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withMinIdle(2));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withAcquireTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withHookTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withMaxRequestsPerWorker(0));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withMaxWorkerAge(Duration.ofMillis(-1)));
    }

    @Test
    void pooledLineSessionMetricsRejectImpossibleSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new PooledLineSessionMetrics(1, 1, 1, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PooledLineSessionMetrics(1, 2, 0, 2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PooledLineSessionMetrics(1, 0, 2, 2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PooledLineSessionMetrics(0, 0, 0, 0, 1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledLineSessionMetrics(
                        1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, Map.of(PooledWorkerRetireReason.TIMEOUT, -1L)));
    }

    @Test
    void protocolSessionOptionsRejectInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionOptions.defaults()
                .withRequestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionOptions.defaults()
                .withTranscriptLimit(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionOptions.defaults()
                .withStdoutBacklogLimit(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionOptions.defaults()
                .withMaxRequestBytes(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionOptions.defaults()
                .withMaxRequestChars(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionOptions.defaults()
                .withMaxResponseBytes(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionOptions.defaults()
                .withMaxResponseChars(0));
    }

    @Test
    void pooledProtocolSessionOptionsRejectInvalidPoolPolicies() {
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withMaxSize(0));
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withWarmupSize(-1));
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withMinIdle(-1));
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withWarmupSize(2));
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withMinIdle(2));
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withAcquireTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withHookTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withMaxRequestsPerWorker(0));
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionOptions.defaults()
                .withMaxWorkerAge(Duration.ofMillis(-1)));
    }

    @Test
    void pooledProtocolSessionMetricsRejectImpossibleSnapshots() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(
                        1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, Map.of(PooledWorkerRetireReason.TIMEOUT, -1L)));
    }

    @Test
    void pooledProtocolSessionMetricsSnapshotRetireReasons() {
        EnumMap<PooledWorkerRetireReason, Long> retireReasons = new EnumMap<>(PooledWorkerRetireReason.class);
        retireReasons.put(PooledWorkerRetireReason.MAX_REQUESTS, 1L);

        PooledProtocolSessionMetrics metrics =
                new PooledProtocolSessionMetrics(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, retireReasons);
        retireReasons.put(PooledWorkerRetireReason.TIMEOUT, 1L);

        assertEquals(Map.of(PooledWorkerRetireReason.MAX_REQUESTS, 1L), metrics.retireReasons());
        assertThrows(UnsupportedOperationException.class, () -> metrics.retireReasons()
                .put(PooledWorkerRetireReason.TIMEOUT, 1L));
        assertThrows(
                NullPointerException.class,
                () -> new PooledProtocolSessionMetrics(1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, null));
    }
}
