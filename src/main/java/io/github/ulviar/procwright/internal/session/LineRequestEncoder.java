/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

final class LineRequestEncoder {

    private LineRequestEncoder() {}

    private static Prepared prepare(
            String line,
            LineSessionSettings options,
            Function<String, ? extends RuntimeException> tooLarge,
            Runnable checkpoint) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(tooLarge, "tooLarge");
        Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.run();
        validate(line);
        checkpoint.run();
        if (line.length() > options.maxRequestChars()) {
            throw tooLarge.apply("Line request exceeds maxRequestChars");
        }
        CharSequence terminated = new LineFeedTerminatedText(line);
        long encodedLength = BoundedTextEncoder.encodedLengthUpTo(
                terminated, options.charset(), options.maxRequestBytes(), checkpoint);
        if (encodedLength > options.maxRequestBytes()) {
            throw tooLarge.apply("Encoded line request exceeds maxRequestBytes");
        }
        return new Prepared(line, options.charset(), (int) encodedLength);
    }

    static void validate(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain line separators");
        }
    }

    static byte[] encodeUntil(
            String line,
            LineSessionSettings options,
            Function<String, ? extends RuntimeException> tooLarge,
            Supplier<? extends RuntimeException> timeout,
            Function<InterruptedException, ? extends RuntimeException> interrupted,
            long deadlineNanos) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(interrupted, "interrupted");
        return prepareUntil(line, options, tooLarge, timeout, interrupted, deadlineNanos)
                .encodeUntil(timeout, interrupted, deadlineNanos);
    }

    static Prepared prepareUntil(
            String line,
            LineSessionSettings options,
            Function<String, ? extends RuntimeException> tooLarge,
            Supplier<? extends RuntimeException> timeout,
            Function<InterruptedException, ? extends RuntimeException> interrupted,
            long deadlineNanos) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(interrupted, "interrupted");
        return prepare(line, options, tooLarge, () -> ensureCanContinue(deadlineNanos, timeout, interrupted));
    }

    /** Validated immutable input; the encoded array is allocated only when its worker is available. */
    record Prepared(String line, Charset charset, int encodedLength) {

        Prepared {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(charset, "charset");
            if (encodedLength < 0) {
                throw new IllegalArgumentException("encodedLength must not be negative");
            }
        }

        byte[] encodeUntil(
                Supplier<? extends RuntimeException> timeout,
                Function<InterruptedException, ? extends RuntimeException> interrupted,
                long deadlineNanos) {
            Runnable checkpoint = () -> ensureCanContinue(deadlineNanos, timeout, interrupted);
            checkpoint.run();
            return BoundedTextEncoder.encode(new LineFeedTerminatedText(line), charset, encodedLength, checkpoint);
        }
    }

    private static void ensureCanContinue(
            long deadlineNanos,
            Supplier<? extends RuntimeException> timeout,
            Function<InterruptedException, ? extends RuntimeException> interrupted) {
        if (Thread.currentThread().isInterrupted()) {
            throw interrupted.apply(new InterruptedException("Interrupted while encoding line request"));
        }
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw timeout.get();
        }
    }
}
