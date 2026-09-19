/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.RunSettings;
import io.github.ulviar.procwright.internal.SessionSettings;
import io.github.ulviar.procwright.internal.StreamSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withRequestTimeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withTranscriptLimit(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withStdoutBacklogLines(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withStdoutBacklogChars(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withMaxLineChars(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withMaxRequestBytes(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withMaxRequestChars(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withMaxResponseLines(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LineSessionSettings.defaults().withMaxResponseChars(0));
    }

    @Test
    void expectSettingsRejectInvalidTimeoutAndTranscriptLimit() {
        assertThrows(
                IllegalArgumentException.class, () -> ExpectSettings.defaults().withTimeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class, () -> ExpectSettings.defaults().withTranscriptLimit(0));
        assertThrows(
                IllegalArgumentException.class, () -> ExpectSettings.defaults().withMatchBufferLimit(0));
        assertThrows(NullPointerException.class, () -> ExpectSettings.defaults().withOutputCharset(null));
    }

    @Test
    void terminalSizeRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(0, 24));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 0));
    }

    @Test
    void sessionSettingsCarryTerminalDefaults() {
        PtyProvider provider = PtyProvider.unavailable("no test provider");
        SessionSettings options = SessionSettings.defaults(CommandSpec.of("tool"))
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
        WorkerPoolSettings<Object> defaults = poolSettings();
        assertEquals(1, defaults.maxSize());
        assertEquals(Duration.ofSeconds(15), defaults.closeTimeout());
        assertEquals(
                WorkerPoolSettings.MAX_SIZE,
                defaults.withMaxSize(WorkerPoolSettings.MAX_SIZE)
                        .validateForOpen()
                        .maxSize());
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withMaxSize(0));
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withWarmupSize(-1));
        assertThrows(IllegalArgumentException.class, () -> poolSettings().withMinIdle(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings().withWarmupSize(2).validateForOpen());
        assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings().withMinIdle(2).validateForOpen());
        assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings()
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
    }

    @Test
    void workerHooksAreAbsentUntilExplicitlyConfiguredAndSurvivePolicyChanges() {
        WorkerPoolSettings<Object> defaults = poolSettings();
        assertTrue(defaults.resetHook().isEmpty());
        assertTrue(defaults.healthCheck().isEmpty());
        Consumer<Object> reset = worker -> {};
        Predicate<Object> health = worker -> true;

        WorkerPoolSettings<Object> configured = defaults.withResetHook(reset)
                .withHealthCheck(health)
                .withMaxSize(2)
                .withHookTimeout(Duration.ofSeconds(1));

        assertSame(reset, configured.resetHook().orElseThrow());
        assertSame(health, configured.healthCheck().orElseThrow());
        assertTrue(defaults.resetHook().isEmpty());
        assertTrue(defaults.healthCheck().isEmpty());
        assertThrows(NullPointerException.class, () -> defaults.withResetHook(null));
        assertThrows(NullPointerException.class, () -> defaults.withHealthCheck(null));
    }

    @Test
    void workerPoolCrossFieldValidationIsIndependentOfSetterOrder() {
        WorkerPoolSettings<Object> limitsLast =
                poolSettings().withWarmupSize(2).withMinIdle(2).withMaxSize(2).validateForOpen();
        WorkerPoolSettings<Object> limitsFirst =
                poolSettings().withMaxSize(2).withMinIdle(2).withWarmupSize(2).validateForOpen();

        assertEquals(2, limitsLast.maxSize());
        assertEquals(2, limitsLast.warmupSize());
        assertEquals(2, limitsLast.minIdle());
        assertEquals(2, limitsFirst.maxSize());
        assertEquals(2, limitsFirst.warmupSize());
        assertEquals(2, limitsFirst.minIdle());

        IllegalArgumentException warmupThenMax = assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings().withWarmupSize(2).withMaxSize(1).validateForOpen());
        IllegalArgumentException maxThenWarmup = assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings().withMaxSize(1).withWarmupSize(2).validateForOpen());
        IllegalArgumentException minIdleThenMax = assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings().withMinIdle(2).withMaxSize(1).validateForOpen());
        IllegalArgumentException maxThenMinIdle = assertThrows(
                IllegalArgumentException.class,
                () -> poolSettings().withMaxSize(1).withMinIdle(2).validateForOpen());

        assertEquals("warmupSize must not exceed maxSize", warmupThenMax.getMessage());
        assertEquals(warmupThenMax.getMessage(), maxThenWarmup.getMessage());
        assertEquals("minIdle must not exceed maxSize", minIdleThenMax.getMessage());
        assertEquals(minIdleThenMax.getMessage(), maxThenMinIdle.getMessage());
    }

    @Test
    void protocolSessionSettingsRejectInvalidLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolSessionSettings.defaults().withTranscriptLimit(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolSessionSettings.defaults().withOutputBacklogLimit(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolSessionSettings.defaults().withMaxRequestBytes(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolSessionSettings.defaults().withMaxRequestChars(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolSessionSettings.defaults().withMaxResponseBytes(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolSessionSettings.defaults().withMaxResponseChars(0));
    }

    private static RunSettings runSettings() {
        return RunSettings.defaults(CommandSpec.of("tool"));
    }

    private static StreamSettings streamSettings() {
        return StreamSettings.defaults(CommandSpec.of("tool"));
    }

    private static WorkerPoolSettings<Object> poolSettings() {
        return WorkerPoolSettings.defaults();
    }
}
