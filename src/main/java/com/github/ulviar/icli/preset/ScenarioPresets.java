package com.github.ulviar.icli.preset;

import com.github.ulviar.icli.command.CapturePolicy;
import com.github.ulviar.icli.command.CommandInvocation;
import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.command.OutputMode;
import com.github.ulviar.icli.session.Expect;
import com.github.ulviar.icli.session.LineSessionInvocation;
import com.github.ulviar.icli.session.PooledLineSessionInvocation;
import com.github.ulviar.icli.session.SessionInvocation;
import com.github.ulviar.icli.session.StreamInvocation;
import com.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reusable scenario presets for common command workflows.
 *
 * <p>Presets are typed builder customizers. They do not launch processes, allocate runtime resources, or create
 * scenario-specific runners.
 */
public final class ScenarioPresets {

    private ScenarioPresets() {}

    /**
     * Returns a one-shot command automation preset with bounded capture and separate stdout/stderr.
     *
     * @param timeout command timeout
     * @param captureBytes bounded capture size
     * @return run invocation preset
     */
    public static Consumer<CommandInvocation.Builder> commandAutomation(Duration timeout, int captureBytes) {
        Duration requiredTimeout = requirePositive(timeout, "timeout");
        int requiredCaptureBytes = requirePositive(captureBytes, "captureBytes");
        return invocation -> invocation
                .timeout(requiredTimeout)
                .capture(CapturePolicy.bounded(requiredCaptureBytes))
                .output(OutputMode.SEPARATE);
    }

    /**
     * Returns a one-shot environment diagnostics preset with merged UTF-8 output.
     *
     * @param timeout command timeout
     * @param captureBytes bounded capture size
     * @return run invocation preset
     */
    public static Consumer<CommandInvocation.Builder> environmentDiagnostics(Duration timeout, int captureBytes) {
        Duration requiredTimeout = requirePositive(timeout, "timeout");
        int requiredCaptureBytes = requirePositive(captureBytes, "captureBytes");
        return invocation -> invocation
                .timeout(requiredTimeout)
                .capture(CapturePolicy.bounded(requiredCaptureBytes))
                .charset(StandardCharsets.UTF_8)
                .output(OutputMode.MERGED);
    }

    /**
     * Returns a one-shot binary output capture preset.
     *
     * <p>The preset intentionally does not set a text charset. Captured bytes are available from
     * {@link CommandResult#stdoutBytes()} and {@link CommandResult#stderrBytes()}.
     *
     * @param timeout command timeout
     * @param captureBytes bounded capture size
     * @return run invocation preset
     */
    public static Consumer<CommandInvocation.Builder> binaryOutputCapture(Duration timeout, int captureBytes) {
        Duration requiredTimeout = requirePositive(timeout, "timeout");
        int requiredCaptureBytes = requirePositive(captureBytes, "captureBytes");
        return invocation -> invocation
                .timeout(requiredTimeout)
                .capture(CapturePolicy.bounded(requiredCaptureBytes))
                .output(OutputMode.SEPARATE);
    }

    /**
     * Returns a line-oriented REPL preset.
     *
     * @param idleTimeout session idle timeout
     * @param terminalPolicy terminal policy
     * @return line-session invocation preset
     */
    public static Consumer<LineSessionInvocation.Builder> replLineMode(
            Duration idleTimeout, TerminalPolicy terminalPolicy) {
        Duration requiredIdleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
        TerminalPolicy requiredTerminalPolicy = Objects.requireNonNull(terminalPolicy, "terminalPolicy");
        return invocation -> invocation.idleTimeout(requiredIdleTimeout).terminal(requiredTerminalPolicy);
    }

    /**
     * Returns an interactive prompt-automation preset for sessions that will be wrapped by {@link Expect}.
     *
     * @param idleTimeout session idle timeout
     * @param terminalPolicy terminal policy
     * @return interactive-session invocation preset
     */
    public static Consumer<SessionInvocation.Builder> promptAutomationSession(
            Duration idleTimeout, TerminalPolicy terminalPolicy) {
        Duration requiredIdleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
        TerminalPolicy requiredTerminalPolicy = Objects.requireNonNull(terminalPolicy, "terminalPolicy");
        return invocation -> invocation
                .idleTimeout(requiredIdleTimeout)
                .terminal(requiredTerminalPolicy)
                .charset(StandardCharsets.UTF_8);
    }

    /**
     * Returns a listen-only log-following preset.
     *
     * @param timeout absolute stream timeout, or {@link Duration#ZERO} to disable it
     * @return streaming invocation preset
     */
    public static Consumer<StreamInvocation.Builder> logFollowing(Duration timeout) {
        Duration requiredTimeout = requireNonNegative(timeout, "timeout");
        return invocation -> invocation.timeout(requiredTimeout).closeStdinOnStart();
    }

    /**
     * Returns a terminal-required interactive session preset.
     *
     * @param idleTimeout session idle timeout
     * @return interactive-session invocation preset
     */
    public static Consumer<SessionInvocation.Builder> terminalRequiredSession(Duration idleTimeout) {
        Duration requiredIdleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
        return invocation -> invocation.idleTimeout(requiredIdleTimeout).terminal(TerminalPolicy.REQUIRED);
    }

    /**
     * Returns a warm worker pool preset.
     *
     * @param maxSize maximum live workers
     * @param warmupSize workers opened when the pool is created
     * @param acquireTimeout maximum time to wait for an available worker
     * @return pooled line-session invocation preset
     */
    public static Consumer<PooledLineSessionInvocation.Builder> warmWorkerPool(
            int maxSize, int warmupSize, Duration acquireTimeout) {
        int requiredMaxSize = requirePositive(maxSize, "maxSize");
        if (warmupSize < 0) {
            throw new IllegalArgumentException("warmupSize must not be negative");
        }
        if (warmupSize > requiredMaxSize) {
            throw new IllegalArgumentException("warmupSize must not exceed maxSize");
        }
        Duration requiredAcquireTimeout = requirePositive(acquireTimeout, "acquireTimeout");
        return invocation ->
                invocation.maxSize(requiredMaxSize).warmupSize(warmupSize).acquireTimeout(requiredAcquireTimeout);
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
