/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Completed one-shot command result.
 *
 * <p>A nonzero exit code or an ordinary execution timeout is represented by a result rather than thrown as an
 * exception. Use {@link #succeeded()} for the exit/timeout check and inspect truncation flags separately before parsing
 * output that must be complete. Launch, I/O, supervision, and decoding failures use {@link CommandExecutionException}.
 *
 * <p>The result is immutable: byte arrays are copied on construction and access, and equality compares their contents.
 * In results produced by Procwright, captured bytes preserve the original output and text uses the selected
 * {@link CharsetPolicy} without newline
 * normalization. If a capture limit cuts through a multibyte character, the text omits that incomplete trailing
 * character while the byte view retains its captured bytes.
 *
 * <p>For streams that were discarded or redirected to files through {@link CapturePolicy#discard()} or
 * {@link CapturePolicy#toPath}, the text and byte accessors return empty values and the truncation flags stay
 * {@code false}; exit code, {@link #timedOut()}, and {@link #elapsed()} are reported as usual.
 *
 * @param exitCode process exit code, or empty when no exit status is available
 * @param stdoutBytes captured standard output bytes
 * @param stderrBytes captured standard error bytes
 * @param stdout captured standard output
 * @param stderr captured standard error
 * @param stdoutTruncated whether stdout exceeded the capture limit
 * @param stderrTruncated whether stderr exceeded the capture limit
 * @param timedOut whether the execution deadline triggered shutdown; does not imply an absent or nonzero exit code
 * @param elapsed non-negative elapsed duration including launch, supervision, and synchronous cleanup
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
     * <p>The byte views are produced by encoding the provided text as UTF-8 regardless of the charset the command
     * actually produced. Use {@link #CommandResult(int, String, String, Charset)} when the output charset differs.
     * The elapsed duration is zero, and timeout and truncation flags are false.
     *
     * @param exitCode process exit code
     * @param stdout captured standard output
     * @param stderr captured standard error
     */
    public CommandResult(int exitCode, String stdout, String stderr) {
        this(exitCode, stdout, stderr, StandardCharsets.UTF_8);
    }

    /**
     * Creates a completed command result without truncation or timeout metadata, encoding the byte views with the
     * provided charset.
     *
     * <p>The elapsed duration is zero, and timeout and truncation flags are false. Encoding follows
     * {@link String#getBytes(Charset)}, including replacement of malformed or unmappable input.
     *
     * @param exitCode process exit code
     * @param stdout captured standard output
     * @param stderr captured standard error
     * @param charset charset used to derive the stdout and stderr byte views from the text
     */
    public CommandResult(int exitCode, String stdout, String stderr, Charset charset) {
        this(
                OptionalInt.of(exitCode),
                requireText(stdout, "stdout").getBytes(Objects.requireNonNull(charset, "charset")),
                requireText(stderr, "stderr").getBytes(charset),
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
     * <p>This advanced constructor accepts already decoded text alongside the captured bytes. Results produced by Procwright
     * keep those values aligned through the execution charset. Manually created snapshots are responsible for providing
     * consistent text and byte views. Both byte arrays are defensively copied. This constructor validates only
     * non-null components and non-negative elapsed time; it does not infer or reconcile exit, timeout, or truncation flags.
     *
     * @param exitCode process exit code, or empty when no exit status is available
     * @param stdoutBytes captured standard output bytes
     * @param stderrBytes captured standard error bytes
     * @param stdout captured standard output
     * @param stderr captured standard error
     * @param stdoutTruncated whether stdout exceeded the capture limit
     * @param stderrTruncated whether stderr exceeded the capture limit
     * @param timedOut whether the execution deadline triggered shutdown
     * @param elapsed non-negative elapsed duration including launch, supervision, and synchronous cleanup
     * @throws IllegalArgumentException if {@code elapsed} is negative
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
     * <p>The accessor clones the backing array on every call. Cache the returned array when repeatedly accessing
     * large outputs.
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
     * <p>The accessor clones the backing array on every call. Cache the returned array when repeatedly accessing
     * large outputs.
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
     * <p>Truncation does not make an otherwise successful result fail this check. When consuming complete output is
     * required, also inspect {@link #stdoutTruncated()} and {@link #stderrTruncated()}.
     *
     * @return {@code true} when this result did not time out and {@link #exitCode()} is zero
     */
    public boolean succeeded() {
        return !timedOut && exitCode.isPresent() && exitCode.orElseThrow() == 0;
    }

    /**
     * Converts this result into an exception that preserves the result.
     *
     * <p>This method creates an exception without throwing it and does not check {@link #succeeded()}. Callers normally
     * use {@code throw result.toException()} only after deciding the result is unsuccessful.
     *
     * @return exception for this command result
     */
    public CommandException toException() {
        return new CommandException(this);
    }

    @Override
    public boolean equals(@Nullable Object other) {
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
