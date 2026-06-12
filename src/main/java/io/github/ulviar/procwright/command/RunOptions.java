/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Default policies applied to one-shot command invocations.
 */
public final class RunOptions {

    private static final RunOptions DEFAULTS = new RunOptions(
            CapturePolicy.bounded(1024 * 1024),
            ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)),
            Duration.ofSeconds(30),
            StandardCharsets.UTF_8,
            OutputMode.SEPARATE);

    private final CapturePolicy capturePolicy;
    private final ShutdownPolicy shutdownPolicy;
    private final Duration timeout;
    private final CharsetPolicy charsetPolicy;
    private final OutputMode outputMode;

    /**
     * Creates run options from explicit policies.
     *
     * @param capturePolicy default capture policy
     * @param shutdownPolicy default shutdown policy
     */
    public RunOptions(CapturePolicy capturePolicy, ShutdownPolicy shutdownPolicy) {
        this(capturePolicy, shutdownPolicy, Duration.ofSeconds(30), StandardCharsets.UTF_8, OutputMode.SEPARATE);
    }

    /**
     * Creates run options from explicit policies.
     *
     * @param capturePolicy default capture policy
     * @param shutdownPolicy default shutdown policy
     * @param timeout default timeout, or {@link Duration#ZERO} to disable it
     * @param charset default output charset
     * @param outputMode default output mode
     */
    public RunOptions(
            CapturePolicy capturePolicy,
            ShutdownPolicy shutdownPolicy,
            Duration timeout,
            Charset charset,
            OutputMode outputMode) {
        this(capturePolicy, shutdownPolicy, timeout, CharsetPolicy.replace(charset), outputMode);
    }

    /**
     * Creates run options from explicit policies.
     *
     * @param capturePolicy default capture policy
     * @param shutdownPolicy default shutdown policy
     * @param timeout default timeout, or {@link Duration#ZERO} to disable it
     * @param charsetPolicy default output charset policy
     * @param outputMode default output mode
     */
    public RunOptions(
            CapturePolicy capturePolicy,
            ShutdownPolicy shutdownPolicy,
            Duration timeout,
            CharsetPolicy charsetPolicy,
            OutputMode outputMode) {
        this.capturePolicy = Objects.requireNonNull(capturePolicy, "capturePolicy");
        this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        this.timeout = requireNonNegative(timeout, "timeout");
        this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        this.outputMode = Objects.requireNonNull(outputMode, "outputMode");
    }

    /**
     * Returns the default one-shot run options: timeout 30 seconds, capture {@code bounded(1 MiB)} per stream,
     * shutdown {@code interruptThenKill(2 s, 5 s)}, charset policy {@code CharsetPolicy.replace(UTF-8)}, and output
     * mode {@link OutputMode#SEPARATE}.
     *
     * @return default run options
     */
    public static RunOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different default timeout.
     *
     * <p>Overrides the 30-second default. {@link Duration#ZERO} disables the run timeout: the process is awaited
     * until it exits on its own, while the shutdown policy still applies on failure paths. Negative values are
     * rejected.
     *
     * @param timeout run timeout, or {@link Duration#ZERO} to disable it
     * @return updated options
     */
    public RunOptions withTimeout(Duration timeout) {
        return new RunOptions(capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode);
    }

    /**
     * Returns a copy with a different default capture policy.
     *
     * <p>Overrides the default {@code CapturePolicy.bounded(1 MiB)} per-stream in-memory capture.
     *
     * @param capturePolicy capture policy
     * @return updated options
     */
    public RunOptions withCapture(CapturePolicy capturePolicy) {
        return new RunOptions(capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode);
    }

    /**
     * Returns a copy with a different default shutdown policy.
     *
     * <p>Overrides the default {@code ShutdownPolicy.interruptThenKill(2 s, 5 s)} escalation.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated options
     */
    public RunOptions withShutdown(ShutdownPolicy shutdownPolicy) {
        return new RunOptions(capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode);
    }

    /**
     * Returns a copy with a different default output charset policy.
     *
     * <p>Overrides the default {@code CharsetPolicy.replace(UTF-8)} forgiving decoding.
     *
     * @param charsetPolicy output charset policy
     * @return updated options
     */
    public RunOptions withCharsetPolicy(CharsetPolicy charsetPolicy) {
        return new RunOptions(capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode);
    }

    /**
     * Returns a copy with a different default output mode.
     *
     * <p>Overrides the default {@link OutputMode#SEPARATE} independent stream capture.
     *
     * @param outputMode output mode
     * @return updated options
     */
    public RunOptions withOutputMode(OutputMode outputMode) {
        return new RunOptions(capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode);
    }

    /**
     * Returns the default capture policy.
     *
     * @return capture policy
     */
    public CapturePolicy capturePolicy() {
        return capturePolicy;
    }

    /**
     * Returns the default shutdown policy.
     *
     * @return shutdown policy
     */
    public ShutdownPolicy shutdownPolicy() {
        return shutdownPolicy;
    }

    /**
     * Returns the default timeout.
     *
     * @return timeout, or {@link Duration#ZERO} when the run timeout is disabled
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the default output charset.
     *
     * @return output charset
     */
    public Charset charset() {
        return charsetPolicy.charset();
    }

    /**
     * Returns the default output charset decoding policy.
     *
     * @return output charset policy
     */
    public CharsetPolicy charsetPolicy() {
        return charsetPolicy;
    }

    /**
     * Returns the default output mode.
     *
     * @return output mode
     */
    public OutputMode outputMode() {
        return outputMode;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RunOptions that)) {
            return false;
        }
        return capturePolicy.equals(that.capturePolicy)
                && shutdownPolicy.equals(that.shutdownPolicy)
                && timeout.equals(that.timeout)
                && charsetPolicy.equals(that.charsetPolicy)
                && outputMode == that.outputMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode);
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
