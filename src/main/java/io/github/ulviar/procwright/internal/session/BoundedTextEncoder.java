/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

/** Encodes text with fixed-size working buffers and a preflight byte limit. */
final class BoundedTextEncoder {

    private static final int BUFFER_SIZE = 8192;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private BoundedTextEncoder() {}

    static long encodedLengthUpTo(CharSequence text, Charset charset, long byteLimit, Runnable checkpoint) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (byteLimit < 0) {
            throw new IllegalArgumentException("byteLimit must not be negative");
        }

        CharsetEncoder encoder = replacingEncoder(charset);
        CharBuffer input = CharBuffer.wrap(text);
        ByteBuffer output = ByteBuffer.allocate(BUFFER_SIZE);
        long encodedBytes = 0;
        checkpoint.run();
        while (true) {
            CoderResult result = encoder.encode(input, output, true);
            encodedBytes += output.position();
            output.clear();
            if (encodedBytes > byteLimit) {
                return byteLimit + 1;
            }
            checkpoint.run();
            if (result.isOverflow()) {
                continue;
            }
            requireSuccess(result);
            break;
        }
        while (true) {
            CoderResult result = encoder.flush(output);
            encodedBytes += output.position();
            output.clear();
            if (encodedBytes > byteLimit) {
                return byteLimit + 1;
            }
            checkpoint.run();
            if (result.isOverflow()) {
                continue;
            }
            requireSuccess(result);
            return encodedBytes;
        }
    }

    static byte[] encode(CharSequence text, Charset charset, int encodedLength, Runnable checkpoint) {
        byte[] result = new byte[encodedLength];
        int[] offset = {0};
        try {
            write(text, charset, checkpoint, (bytes, count) -> {
                System.arraycopy(bytes, 0, result, offset[0], count);
                offset[0] += count;
            });
        } catch (IOException impossible) {
            throw new AssertionError("in-memory encoding failed", impossible);
        }
        if (offset[0] != encodedLength) {
            throw new IllegalStateException("encoded length changed between validation and encoding");
        }
        return result;
    }

    static EncodedText encodeUpTo(CharSequence text, Charset charset, int byteLimit, Runnable checkpoint) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (byteLimit < 0) {
            throw new IllegalArgumentException("byteLimit must not be negative");
        }

        CharsetEncoder encoder = replacingEncoder(charset);
        CharBuffer input = CharBuffer.wrap(text);
        ByteBuffer output = ByteBuffer.allocate(Math.min(BUFFER_SIZE, Math.max(1, byteLimit)));
        BoundedBytes encoded = new BoundedBytes(byteLimit);
        checkpoint.run();
        while (true) {
            int inputPosition = input.position();
            CoderResult result = encoder.encode(input, output, true);
            requireSuccess(result);
            requireProgress(result, inputPosition, input.position(), output.position());
            if (!encoded.append(output)) {
                return null;
            }
            checkpoint.run();
            if (result.isOverflow()) {
                continue;
            }
            break;
        }
        while (true) {
            CoderResult result = encoder.flush(output);
            requireSuccess(result);
            requireFlushProgress(result, output.position());
            if (!encoded.append(output)) {
                return null;
            }
            checkpoint.run();
            if (result.isOverflow()) {
                continue;
            }
            return encoded.result();
        }
    }

    static void write(CharSequence text, Charset charset, Runnable checkpoint, ByteSink sink) throws IOException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(sink, "sink");

        CharsetEncoder encoder = replacingEncoder(charset);
        CharBuffer input = CharBuffer.wrap(text);
        ByteBuffer output = ByteBuffer.allocate(BUFFER_SIZE);
        checkpoint.run();
        while (true) {
            CoderResult result = encoder.encode(input, output, true);
            emit(output, sink);
            checkpoint.run();
            if (result.isOverflow()) {
                continue;
            }
            requireSuccess(result);
            break;
        }
        while (true) {
            CoderResult result = encoder.flush(output);
            emit(output, sink);
            checkpoint.run();
            if (result.isOverflow()) {
                continue;
            }
            requireSuccess(result);
            return;
        }
    }

    private static CharsetEncoder replacingEncoder(Charset charset) {
        return charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    private static void emit(ByteBuffer output, ByteSink sink) throws IOException {
        int count = output.position();
        if (count > 0) {
            sink.write(output.array(), count);
        }
        output.clear();
    }

    private static void requireSuccess(CoderResult result) {
        if (result.isError()) {
            throw new IllegalStateException("replacing charset encoder reported an error");
        }
    }

    private static void requireProgress(
            CoderResult result, int previousInputPosition, int inputPosition, int outputCount) {
        if (inputPosition < previousInputPosition) {
            throw new IllegalStateException("Charset encoder moved input position backwards from "
                    + previousInputPosition
                    + " to "
                    + inputPosition);
        }
        if (result.isOverflow() && inputPosition == previousInputPosition && outputCount == 0) {
            throw new IllegalStateException(
                    "Charset encoder reported overflow without consuming input or producing output");
        }
    }

    private static void requireFlushProgress(CoderResult result, int outputCount) {
        if (result.isOverflow() && outputCount == 0) {
            throw new IllegalStateException("Charset encoder flush reported overflow without producing output");
        }
    }

    @FunctionalInterface
    interface ByteSink {

        void write(byte[] bytes, int count) throws IOException;
    }

    record EncodedText(byte[] bytes, int length) {

        EncodedText {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(0, length, bytes.length);
        }
    }

    private static final class BoundedBytes {

        private final int limit;
        private byte[] bytes = EMPTY_BYTES;
        private int length;

        private BoundedBytes(int limit) {
            this.limit = limit;
        }

        private boolean append(ByteBuffer output) {
            int count = output.position();
            if (count > limit - length) {
                output.clear();
                return false;
            }
            ensureCapacity(length + count);
            if (count > 0) {
                System.arraycopy(output.array(), 0, bytes, length, count);
                length += count;
            }
            output.clear();
            return true;
        }

        private void ensureCapacity(int required) {
            if (required <= bytes.length) {
                return;
            }
            int doubled = bytes.length > limit - bytes.length ? limit : bytes.length * 2;
            int capacity = Math.max(required, Math.max(1, doubled));
            bytes = java.util.Arrays.copyOf(bytes, capacity);
        }

        private EncodedText result() {
            return new EncodedText(bytes, length);
        }
    }
}
