package com.github.ulviar.icli;

import java.time.Duration;
import java.util.Objects;

/**
 * Default policies applied to one-shot command invocations.
 */
public final class RunOptions {

    private static final RunOptions DEFAULTS = new RunOptions(
            CapturePolicy.bounded(1024 * 1024),
            ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)));

    private final CapturePolicy capturePolicy;
    private final ShutdownPolicy shutdownPolicy;

    /**
     * Creates run options from explicit policies.
     *
     * @param capturePolicy default capture policy
     * @param shutdownPolicy default shutdown policy
     */
    public RunOptions(CapturePolicy capturePolicy, ShutdownPolicy shutdownPolicy) {
        this.capturePolicy = Objects.requireNonNull(capturePolicy, "capturePolicy");
        this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RunOptions that)) {
            return false;
        }
        return capturePolicy.equals(that.capturePolicy) && shutdownPolicy.equals(that.shutdownPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(capturePolicy, shutdownPolicy);
    }
}
