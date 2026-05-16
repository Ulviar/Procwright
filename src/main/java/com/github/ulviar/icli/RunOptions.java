package com.github.ulviar.icli;

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
    private final Charset charset;
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
     * @param timeout default timeout
     * @param charset default output charset
     * @param outputMode default output mode
     */
    public RunOptions(
            CapturePolicy capturePolicy,
            ShutdownPolicy shutdownPolicy,
            Duration timeout,
            Charset charset,
            OutputMode outputMode) {
        this.capturePolicy = Objects.requireNonNull(capturePolicy, "capturePolicy");
        this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        this.timeout = requireNonNegative(timeout, "timeout");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.outputMode = Objects.requireNonNull(outputMode, "outputMode");
    }

    /**
     * Returns the default one-shot run options.
     *
     * @return default run options
     */
    public static RunOptions defaults() {
        return DEFAULTS;
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
     * @return timeout
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
        return charset;
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
                && charset.equals(that.charset)
                && outputMode == that.outputMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(capturePolicy, shutdownPolicy, timeout, charset, outputMode);
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
