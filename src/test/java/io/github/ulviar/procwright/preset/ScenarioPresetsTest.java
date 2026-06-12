/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.preset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandInvocation;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.session.LineSessionInvocation;
import io.github.ulviar.procwright.session.PooledLineSessionInvocation;
import io.github.ulviar.procwright.session.SessionInvocation;
import io.github.ulviar.procwright.session.StreamInvocation;
import io.github.ulviar.procwright.session.StreamStdinPolicy;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ScenarioPresetsTest {

    @Test
    void commandAutomationPresetConfiguresBoundedOneShotDefaults() {
        CommandInvocation invocation = applyRun(ScenarioPresets.commandAutomation(Duration.ofSeconds(3), 4096));

        assertEquals(Duration.ofSeconds(3), invocation.timeout().orElseThrow());
        assertEquals(CapturePolicy.bounded(4096), invocation.capturePolicy().orElseThrow());
        assertEquals(OutputMode.SEPARATE, invocation.outputMode().orElseThrow());
    }

    @Test
    void environmentDiagnosticsPresetMergesStderrAndUsesUtf8() {
        CommandInvocation invocation = applyRun(ScenarioPresets.environmentDiagnostics(Duration.ofSeconds(2), 2048));

        assertEquals(Duration.ofSeconds(2), invocation.timeout().orElseThrow());
        assertEquals(CapturePolicy.bounded(2048), invocation.capturePolicy().orElseThrow());
        assertEquals(StandardCharsets.UTF_8, invocation.charset().orElseThrow());
        assertEquals(OutputMode.MERGED, invocation.outputMode().orElseThrow());
    }

    @Test
    void binaryOutputCapturePresetDoesNotForceTextCharset() {
        CommandInvocation invocation = applyRun(ScenarioPresets.binaryOutputCapture(Duration.ofSeconds(4), 8192));

        assertEquals(Duration.ofSeconds(4), invocation.timeout().orElseThrow());
        assertEquals(CapturePolicy.bounded(8192), invocation.capturePolicy().orElseThrow());
        assertFalse(invocation.charset().isPresent());
        assertEquals(OutputMode.SEPARATE, invocation.outputMode().orElseThrow());
    }

    @Test
    void replLineModePresetConfiguresLineSessionLifecycle() {
        LineSessionInvocation invocation =
                applyLine(ScenarioPresets.replLineMode(Duration.ofMinutes(1), TerminalPolicy.AUTO));

        assertEquals(Duration.ofMinutes(1), invocation.idleTimeout().orElseThrow());
        assertEquals(TerminalPolicy.AUTO, invocation.terminalPolicy().orElseThrow());
    }

    @Test
    void promptAutomationSessionPresetConfiguresRawInteractiveLifecycle() {
        SessionInvocation invocation =
                applySession(ScenarioPresets.promptAutomationSession(Duration.ofSeconds(30), TerminalPolicy.REQUIRED));

        assertEquals(Duration.ofSeconds(30), invocation.idleTimeout().orElseThrow());
        assertEquals(TerminalPolicy.REQUIRED, invocation.terminalPolicy().orElseThrow());
        assertEquals(StandardCharsets.UTF_8, invocation.charset().orElseThrow());
    }

    @Test
    void terminalRequiredSessionPresetConfiguresTerminalRequiredLifecycle() {
        SessionInvocation invocation = applySession(ScenarioPresets.terminalRequiredSession(Duration.ofSeconds(15)));

        assertEquals(Duration.ofSeconds(15), invocation.idleTimeout().orElseThrow());
        assertEquals(TerminalPolicy.REQUIRED, invocation.terminalPolicy().orElseThrow());
    }

    @Test
    void logFollowingPresetConfiguresListenWithoutInput() {
        StreamInvocation invocation = applyStream(ScenarioPresets.logFollowing(Duration.ofSeconds(10)));

        assertEquals(Duration.ofSeconds(10), invocation.timeout().orElseThrow());
        assertEquals(StreamStdinPolicy.CLOSE_ON_START, invocation.stdinPolicy());
    }

    @Test
    void warmWorkerPoolPresetConfiguresPoolPolicies() {
        PooledLineSessionInvocation invocation =
                applyPool(ScenarioPresets.warmWorkerPool(4, 2, Duration.ofMillis(250)));

        assertEquals(4, invocation.options().maxSize());
        assertEquals(2, invocation.options().warmupSize());
        assertEquals(Duration.ofMillis(250), invocation.options().acquireTimeout());
    }

    @Test
    void presetsRejectInvalidPolicies() {
        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.commandAutomation(Duration.ZERO, 1024));
        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.environmentDiagnostics(Duration.ZERO, 1024));
        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.binaryOutputCapture(Duration.ZERO, 1024));
        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.commandAutomation(Duration.ofSeconds(1), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScenarioPresets.replLineMode(Duration.ofSeconds(-1), TerminalPolicy.DISABLED));
        assertThrows(NullPointerException.class, () -> ScenarioPresets.replLineMode(Duration.ofSeconds(1), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScenarioPresets.promptAutomationSession(Duration.ofSeconds(-1), TerminalPolicy.DISABLED));
        assertThrows(
                NullPointerException.class, () -> ScenarioPresets.promptAutomationSession(Duration.ofSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.logFollowing(Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class, () -> ScenarioPresets.terminalRequiredSession(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.warmWorkerPool(0, 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class, () -> ScenarioPresets.warmWorkerPool(1, -1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.warmWorkerPool(1, 2, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.warmWorkerPool(1, 0, Duration.ZERO));
    }

    private static CommandInvocation applyRun(Consumer<CommandInvocation.Builder> preset) {
        CommandInvocation.Builder builder = CommandInvocation.builder();
        preset.accept(builder);
        return builder.build();
    }

    private static SessionInvocation applySession(Consumer<SessionInvocation.Builder> preset) {
        SessionInvocation.Builder builder = SessionInvocation.builder();
        preset.accept(builder);
        return builder.build();
    }

    private static LineSessionInvocation applyLine(Consumer<LineSessionInvocation.Builder> preset) {
        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();
        preset.accept(builder);
        return builder.build();
    }

    private static StreamInvocation applyStream(Consumer<StreamInvocation.Builder> preset) {
        StreamInvocation.Builder builder = StreamInvocation.builder();
        preset.accept(builder);
        return builder.build();
    }

    private static PooledLineSessionInvocation applyPool(Consumer<PooledLineSessionInvocation.Builder> preset) {
        PooledLineSessionInvocation.Builder builder = PooledLineSessionInvocation.builder();
        preset.accept(builder);
        return builder.build();
    }
}
