/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

final class LineRequestEncoder {

    private LineRequestEncoder() {}

    static byte[] encode(
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
        return BoundedTextEncoder.encode(terminated, options.charset(), (int) encodedLength, checkpoint);
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
        try {
            return BoundedTaskRunner.run(
                    BoundedTaskRunner.TEXT_ENCODINGS,
                    "procwright-line-encoder-",
                    deadlineNanos,
                    () -> encode(line, options, tooLarge, () -> ensureBeforeDeadline(deadlineNanos, timeout)));
        } catch (TimeoutException exception) {
            throw timeout.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw interrupted.apply(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Unexpected checked request-encoding failure", cause);
        }
    }

    private static void ensureBeforeDeadline(long deadlineNanos, Supplier<? extends RuntimeException> timeout) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw timeout.get();
        }
    }
}
