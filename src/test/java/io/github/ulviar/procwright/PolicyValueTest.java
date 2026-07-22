/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LaunchSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.RunSettings;
import io.github.ulviar.procwright.internal.SessionSettings;
import io.github.ulviar.procwright.internal.StreamSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
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
    void discardCaptureIsAValueObject() {
        assertEquals(CapturePolicy.discard(), CapturePolicy.discard());
        assertEquals(CapturePolicy.discard().hashCode(), CapturePolicy.discard().hashCode());
    }

    @Test
    void toPathCaptureRejectsNullAndBlankPaths() {
        assertThrows(NullPointerException.class, () -> CapturePolicy.toPath(null));
        assertThrows(NullPointerException.class, () -> CapturePolicy.toPath(null, java.nio.file.Path.of("err")));
        assertThrows(NullPointerException.class, () -> CapturePolicy.toPath(java.nio.file.Path.of("out"), null));
        assertThrows(IllegalArgumentException.class, () -> CapturePolicy.toPath(java.nio.file.Path.of("")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturePolicy.toPath(java.nio.file.Path.of("out"), java.nio.file.Path.of("")));
    }

    @Test
    void toPathCaptureRejectsIdenticalStdoutAndStderrTargets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturePolicy.toPath(java.nio.file.Path.of("same.log"), java.nio.file.Path.of("same.log")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CapturePolicy.toPath(
                        java.nio.file.Path.of("dir/../same.log"), java.nio.file.Path.of("same.log")));
    }

    @Test
    void toPathCaptureDistinguishesMergedAndSeparateForms() {
        CapturePolicy.ToPath merged = CapturePolicy.toPath(java.nio.file.Path.of("all.log"));
        CapturePolicy.ToPath separate =
                CapturePolicy.toPath(java.nio.file.Path.of("out.log"), java.nio.file.Path.of("err.log"));

        assertEquals(true, merged.merged());
        assertEquals(java.util.Optional.empty(), merged.stderr());
        assertEquals(false, separate.merged());
        assertEquals(java.nio.file.Path.of("err.log"), separate.stderr().orElseThrow());
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
    void runSettingsRejectNegativeTimeout() {
        assertThrows(IllegalArgumentException.class, () -> runSettings().withTimeout(Duration.ofMillis(-1)));
    }

    @Test
    void runSettingsExposeCharsetPolicy() {
        RunSettings options = runSettings().withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));

        assertEquals(StandardCharsets.UTF_8, options.charsetPolicy().charset());
        assertEquals(CharsetPolicy.report(StandardCharsets.UTF_8), options.charsetPolicy());
    }

    @Test
    void lineSessionSettingsRejectInvalidTimeoutAndTranscriptLimit() {
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withRequestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withTranscriptLimit(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withStdoutBacklogLines(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withStdoutBacklogChars(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withMaxLineChars(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withMaxRequestBytes(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withMaxRequestChars(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withMaxResponseLines(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionSettings.defaults()
                .withMaxResponseChars(0));
    }

    @Test
    void expectSettingsRejectInvalidTimeoutAndTranscriptLimit() {
        assertThrows(
                IllegalArgumentException.class, () -> ExpectSettings.defaults().withTimeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class, () -> ExpectSettings.defaults().withTranscriptLimit(0));
        assertThrows(
                IllegalArgumentException.class, () -> ExpectSettings.defaults().withMatchBufferLimit(0));
    }

    @Test
    void terminalSizeRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(0, 24));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 0));
    }

    @Test
    void sessionSettingsCarryTerminalDefaults() {
        PtyProvider provider = PtyProvider.unavailable("no test provider");
        SessionSettings options = SessionSettings.defaults(LaunchSettings.from(CommandSpec.of("tool")))
                .withTerminal(new io.github.ulviar.procwright.internal.TerminalSettings(
                        TerminalPolicy.REQUIRED, provider, new TerminalSize(100, 40)));

        assertEquals(TerminalPolicy.REQUIRED, options.terminal().policy());
        assertEquals(provider, options.terminal().provider());
        assertEquals(new TerminalSize(100, 40), options.terminal().size());
    }

    @Test
    void streamSettingsRejectInvalidDiagnosticLimitAndNegativeTimeout() {
        assertThrows(IllegalArgumentException.class, () -> streamSettings().withDiagnosticLimit(0));
        assertThrows(IllegalArgumentException.class, () -> streamSettings().withTimeout(Duration.ofMillis(-1)));
    }

    @Test
    void workerPoolSettingsRejectInvalidScalarPoliciesAndDeferCrossFieldChecks() {
        assertEquals(Duration.ofSeconds(15), poolSettings().closeTimeout());
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withMaxSize(0));
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withWarmupSize(-1));
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withMinIdle(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings().withWarmupSize(2).validateForOpen());
        assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings().withMinIdle(2).validateForOpen());
        assertThrows(IllegalArgumentException.class, () -> poolSettings()
                .withMaxSize(WorkerPoolSettings.MAX_SIZE + 1)
                .validateForOpen());
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withAcquireTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withHookTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withCloseTimeout(Duration.ZERO));
        assertEquals(
                Duration.ofSeconds(3),
                poolSettings().withCloseTimeout(Duration.ofSeconds(3)).closeTimeout());
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withMaxRequestsPerWorker(0));
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withMaxWorkerAge(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> poolSettings()
                .withMinIdle(1)
                .withBackgroundReplenishment(false)
                .validateForOpen());
    }

    @Test
    void pooledLineSessionMetricsRejectImpossibleSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> basicMetrics(1, 1, 1, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> basicMetrics(1, 2, 0, 2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> basicMetrics(1, 0, 2, 2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> basicMetrics(0, 0, 0, 0, 1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledLineSessionMetrics(
                        1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, Map.of(PooledWorkerRetireReason.TIMEOUT, -1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledLineSessionMetrics(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledLineSessionMetrics(1, 1, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledLineSessionMetrics(2, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledLineSessionMetrics(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledLineSessionMetrics(
                        0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 2, 0, 0, 0, Map.of(PooledWorkerRetireReason.CLOSED, 1L)));
    }

    private static PooledLineSessionMetrics basicMetrics(
            int size, int idle, int leased, long created, long retired, long completed, long failed) {
        return new PooledLineSessionMetrics(
                size, idle, leased, 0, 0, created, retired, completed, failed, 0, 0, 0, 0, 0, Map.of());
    }

    @Test
    void protocolSessionSettingsRejectInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionSettings.defaults()
                .withRequestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionSettings.defaults()
                .withTranscriptLimit(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionSettings.defaults()
                .withOutputBacklogLimit(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionSettings.defaults()
                .withMaxRequestBytes(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionSettings.defaults()
                .withMaxRequestChars(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionSettings.defaults()
                .withMaxResponseBytes(0));
        assertThrows(IllegalArgumentException.class, () -> ProtocolSessionSettings.defaults()
                .withMaxResponseChars(0));
    }

    @Test
    void pooledProtocolSessionMetricsRejectImpossibleSnapshots() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(
                        1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, Map.of(PooledWorkerRetireReason.TIMEOUT, -1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 1, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(2, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PooledProtocolSessionMetrics(1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 2, 0, 0, 0, Map.of()));
    }

    @Test
    void pooledProtocolSessionMetricsSnapshotRetireReasons() {
        EnumMap<PooledWorkerRetireReason, Long> retireReasons = new EnumMap<>(PooledWorkerRetireReason.class);
        retireReasons.put(PooledWorkerRetireReason.MAX_REQUESTS, 1L);

        PooledProtocolSessionMetrics metrics =
                new PooledProtocolSessionMetrics(1, 1, 0, 0, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0, retireReasons);
        retireReasons.put(PooledWorkerRetireReason.TIMEOUT, 1L);

        assertEquals(Map.of(PooledWorkerRetireReason.MAX_REQUESTS, 1L), metrics.retireReasons());
        assertThrows(UnsupportedOperationException.class, () -> metrics.retireReasons()
                .put(PooledWorkerRetireReason.TIMEOUT, 1L));
        assertThrows(
                NullPointerException.class,
                () -> new PooledProtocolSessionMetrics(1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, null));
    }

    private static RunSettings runSettings() {
        return RunSettings.defaults(LaunchSettings.from(CommandSpec.of("tool")));
    }

    private static StreamSettings streamSettings() {
        return StreamSettings.defaults(LaunchSettings.from(CommandSpec.of("tool")));
    }

    private static WorkerPoolSettings<Object> poolSettings() {
        return WorkerPoolSettings.defaults(worker -> {}, worker -> true);
    }
}
