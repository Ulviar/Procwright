/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.preset;

import io.github.ulviar.procwright.InteractiveScenario;
import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.RunScenario;
import io.github.ulviar.procwright.StreamScenario;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Reusable transformations over immutable scenario drafts. */
public final class ScenarioPresets {

    private ScenarioPresets() {}

    /**
     * Applies bounded separate-stream capture and a finite timeout for command automation.
     *
     * <p>This preset preserves the draft's existing text decoding policy.
     *
     * @param draft run draft to transform
     * @param timeout positive execution timeout
     * @param captureBytes positive per-stream capture limit
     * @return transformed draft
     */
    public static RunScenario.Draft commandAutomation(RunScenario.Draft draft, Duration timeout, int captureBytes) {
        return Objects.requireNonNull(draft, "draft")
                .withTimeout(requirePositive(timeout, "timeout"))
                .withCapture(CapturePolicy.bounded(requirePositive(captureBytes, "captureBytes")))
                .withOutput(OutputMode.SEPARATE);
    }

    /**
     * Applies bounded merged UTF-8 capture for environment diagnostics.
     *
     * @param draft run draft to transform
     * @param timeout positive execution timeout
     * @param captureBytes positive merged capture limit
     * @return transformed draft
     */
    public static RunScenario.Draft environmentDiagnostics(
            RunScenario.Draft draft, Duration timeout, int captureBytes) {
        return Objects.requireNonNull(draft, "draft")
                .withTimeout(requirePositive(timeout, "timeout"))
                .withCapture(CapturePolicy.bounded(requirePositive(captureBytes, "captureBytes")))
                .withCharset(StandardCharsets.UTF_8)
                .withOutput(OutputMode.MERGED);
    }

    /**
     * Applies bounded separate-stream binary capture with a forgiving UTF-8 text view.
     *
     * <p>This preset intentionally replaces the draft's existing decoding policy with {@link
     * CharsetPolicy#replace(java.nio.charset.Charset) replacing UTF-8}. Malformed bytes therefore do not turn a
     * completed capture into a decode failure. Exact captured bytes remain available through {@link
     * CommandResult#stdoutBytes()} and {@link CommandResult#stderrBytes()}.
     *
     * @param draft run draft to transform
     * @param timeout positive execution timeout
     * @param captureBytes positive per-stream capture limit
     * @return transformed draft
     */
    public static RunScenario.Draft binaryOutputCapture(RunScenario.Draft draft, Duration timeout, int captureBytes) {
        return Objects.requireNonNull(draft, "draft")
                .withTimeout(requirePositive(timeout, "timeout"))
                .withCapture(CapturePolicy.bounded(requirePositive(captureBytes, "captureBytes")))
                .withOutput(OutputMode.SEPARATE)
                .withCharsetPolicy(CharsetPolicy.replace(StandardCharsets.UTF_8));
    }

    /**
     * Applies line-oriented REPL lifecycle and terminal settings.
     *
     * @param draft line-session draft to transform
     * @param idleTimeout non-negative caller-visible idle timeout
     * @param terminalPolicy terminal requirement
     * @return transformed draft
     */
    public static LineSessionScenario.Draft replLineMode(
            LineSessionScenario.Draft draft, Duration idleTimeout, TerminalPolicy terminalPolicy) {
        return Objects.requireNonNull(draft, "draft")
                .withIdleTimeout(requireNonNegative(idleTimeout, "idleTimeout"))
                .withTerminal(Objects.requireNonNull(terminalPolicy, "terminalPolicy"));
    }

    /**
     * Applies UTF-8 prompt-automation lifecycle and terminal settings.
     *
     * @param draft interactive-session draft to transform
     * @param idleTimeout non-negative caller-visible idle timeout
     * @param terminalPolicy terminal requirement
     * @return transformed draft
     */
    public static InteractiveScenario.Draft promptAutomationSession(
            InteractiveScenario.Draft draft, Duration idleTimeout, TerminalPolicy terminalPolicy) {
        return Objects.requireNonNull(draft, "draft")
                .withIdleTimeout(requireNonNegative(idleTimeout, "idleTimeout"))
                .withTerminal(Objects.requireNonNull(terminalPolicy, "terminalPolicy"))
                .withCharset(StandardCharsets.UTF_8);
    }

    /**
     * Configures a listen-only process for log following. The listen scenario closes stdin when it starts.
     *
     * @param draft stream draft to transform
     * @param timeout non-negative absolute timeout; zero disables it
     * @return transformed draft
     */
    public static StreamScenario.Draft logFollowing(StreamScenario.Draft draft, Duration timeout) {
        return Objects.requireNonNull(draft, "draft").withTimeout(requireNonNegative(timeout, "timeout"));
    }

    /**
     * Requires a real terminal for an interactive process.
     *
     * @param draft interactive-session draft to transform
     * @param idleTimeout non-negative caller-visible idle timeout
     * @return transformed draft
     */
    public static InteractiveScenario.Draft terminalRequiredSession(
            InteractiveScenario.Draft draft, Duration idleTimeout) {
        return Objects.requireNonNull(draft, "draft")
                .withIdleTimeout(requireNonNegative(idleTimeout, "idleTimeout"))
                .withTerminal(TerminalPolicy.REQUIRED);
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
