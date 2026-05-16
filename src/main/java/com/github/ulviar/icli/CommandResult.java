package com.github.ulviar.icli;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Completed one-shot command result.
 *
 * @param exitCode process exit code when available
 * @param stdout captured standard output
 * @param stderr captured standard error
 * @param stdoutTruncated whether stdout exceeded the capture limit
 * @param stderrTruncated whether stderr exceeded the capture limit
 * @param timedOut whether timeout supervision stopped the process
 * @param elapsed elapsed wall-clock time spent running and supervising the command
 */
public record CommandResult(
        OptionalInt exitCode,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        boolean timedOut,
        Duration elapsed) {

    /**
     * Creates a completed command result without truncation or timeout metadata.
     *
     * @param exitCode process exit code
     * @param stdout captured standard output
     * @param stderr captured standard error
     */
    public CommandResult(int exitCode, String stdout, String stderr) {
        this(OptionalInt.of(exitCode), stdout, stderr, false, false, false, Duration.ZERO);
    }

    /**
     * Creates a completed command result.
     */
    public CommandResult {
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }

    /**
     * Returns whether the command exited successfully.
     *
     * @return {@code true} when this result did not time out and {@link #exitCode()} is zero
     */
    public boolean succeeded() {
        return !timedOut && exitCode.isPresent() && exitCode.orElseThrow() == 0;
    }

    /**
     * Converts this result into an exception that preserves the result.
     *
     * @return exception for this command result
     */
    public CommandException toException() {
        return new CommandException(this);
    }
}
