package com.github.ulviar.icli.command;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Completed one-shot command result.
 *
 * @param exitCode process exit code when available
 * @param stdoutBytes captured standard output bytes
 * @param stderrBytes captured standard error bytes
 * @param stdout captured standard output
 * @param stderr captured standard error
 * @param stdoutTruncated whether stdout exceeded the capture limit
 * @param stderrTruncated whether stderr exceeded the capture limit
 * @param timedOut whether timeout supervision stopped the process
 * @param elapsed elapsed wall-clock time spent running and supervising the command
 */
public record CommandResult(
        OptionalInt exitCode,
        byte[] stdoutBytes,
        byte[] stderrBytes,
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
        this(
                OptionalInt.of(exitCode),
                requireText(stdout, "stdout").getBytes(StandardCharsets.UTF_8),
                requireText(stderr, "stderr").getBytes(StandardCharsets.UTF_8),
                requireText(stdout, "stdout"),
                requireText(stderr, "stderr"),
                false,
                false,
                false,
                Duration.ZERO);
    }

    /**
     * Creates a completed command result.
     *
     * <p>This advanced constructor accepts already decoded text alongside the captured bytes. Results produced by iCLI
     * keep those values aligned through the execution charset. Manually created snapshots are responsible for providing
     * consistent text and byte views.
     *
     * @param exitCode process exit code when available
     * @param stdoutBytes captured standard output bytes
     * @param stderrBytes captured standard error bytes
     * @param stdout captured standard output
     * @param stderr captured standard error
     * @param stdoutTruncated whether stdout exceeded the capture limit
     * @param stderrTruncated whether stderr exceeded the capture limit
     * @param timedOut whether timeout supervision stopped the process
     * @param elapsed elapsed wall-clock time spent running and supervising the command
     */
    public CommandResult {
        Objects.requireNonNull(exitCode, "exitCode");
        stdoutBytes = Objects.requireNonNull(stdoutBytes, "stdoutBytes").clone();
        stderrBytes = Objects.requireNonNull(stderrBytes, "stderrBytes").clone();
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }

    /**
     * Returns a copy of captured standard output bytes.
     *
     * @return captured standard output bytes
     */
    @Override
    public byte[] stdoutBytes() {
        return stdoutBytes.clone();
    }

    /**
     * Returns a copy of captured standard error bytes.
     *
     * @return captured standard error bytes
     */
    @Override
    public byte[] stderrBytes() {
        return stderrBytes.clone();
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CommandResult that)) {
            return false;
        }
        return stdoutTruncated == that.stdoutTruncated
                && stderrTruncated == that.stderrTruncated
                && timedOut == that.timedOut
                && exitCode.equals(that.exitCode)
                && Arrays.equals(stdoutBytes, that.stdoutBytes)
                && Arrays.equals(stderrBytes, that.stderrBytes)
                && stdout.equals(that.stdout)
                && stderr.equals(that.stderr)
                && elapsed.equals(that.elapsed);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(exitCode, stdout, stderr, stdoutTruncated, stderrTruncated, timedOut, elapsed);
        result = 31 * result + Arrays.hashCode(stdoutBytes);
        result = 31 * result + Arrays.hashCode(stderrBytes);
        return result;
    }

    private static String requireText(String value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
