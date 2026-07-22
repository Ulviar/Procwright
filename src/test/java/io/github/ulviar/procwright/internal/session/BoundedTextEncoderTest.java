/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class BoundedTextEncoderTest {

    @Test
    void preflightStopsWithoutTraversingAnOversizedInput() {
        AtomicInteger accessedCharacters = new AtomicInteger();
        CharSequence input = new RepeatingText(1_000_000, accessedCharacters);

        long encodedLength = BoundedTextEncoder.encodedLengthUpTo(input, StandardCharsets.UTF_8, 4, () -> {});

        assertEquals(5, encodedLength);
        assertTrue(accessedCharacters.get() < input.length());
    }

    @Test
    void lineFeedViewEncodesLikeThePreviousStringContract() {
        String line = "value-\u20ac";
        CharSequence terminated = new LineFeedTerminatedText(line);
        int length = (int)
                BoundedTextEncoder.encodedLengthUpTo(terminated, StandardCharsets.UTF_8, Integer.MAX_VALUE, () -> {});

        byte[] encoded = BoundedTextEncoder.encode(terminated, StandardCharsets.UTF_8, length, () -> {});

        assertArrayEquals((line + "\n").getBytes(StandardCharsets.UTF_8), encoded);
    }

    @Test
    void boundedEncodingUsesOneEncoderAndReturnsExactLength() {
        CountingCharset charset = new CountingCharset();

        BoundedTextEncoder.EncodedText encoded = BoundedTextEncoder.encodeUpTo("value", charset, 5, () -> {});

        assertNotNull(encoded);
        assertEquals(1, charset.encoderCreations());
        assertEquals(5, encoded.length());
        assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), encoded.bytes());
    }

    @Test
    void boundedEncodingRejectsOverflowWithoutProgress() {
        Charset charset = new NoProgressEncoderCharset();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> BoundedTextEncoder.encodeUpTo("x", charset, 8, () -> {}));

        assertEquals(
                "Charset encoder reported overflow without consuming input or producing output",
                exception.getMessage());
    }

    private record RepeatingText(int length, AtomicInteger accessedCharacters) implements CharSequence {

        private RepeatingText {
            if (length < 0) {
                throw new IllegalArgumentException("length must not be negative");
            }
        }

        @Override
        public char charAt(int index) {
            java.util.Objects.checkIndex(index, length);
            accessedCharacters.incrementAndGet();
            return 'a';
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            java.util.Objects.checkFromToIndex(start, end, length);
            return new RepeatingText(end - start, accessedCharacters);
        }
    }

    private static class CountingCharset extends Charset {

        private final AtomicInteger encoderCreations = new AtomicInteger();

        private CountingCharset() {
            super("X-Procwright-Counting-Encoder", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations.incrementAndGet();
            return passthroughEncoder(this);
        }

        private int encoderCreations() {
            return encoderCreations.get();
        }
    }

    private static final class NoProgressEncoderCharset extends CountingCharset {

        private NoProgressEncoderCharset() {
            super();
        }

        @Override
        public CharsetEncoder newEncoder() {
            return new CharsetEncoder(this, 1, 1) {
                @Override
                protected CoderResult encodeLoop(CharBuffer input, ByteBuffer output) {
                    return CoderResult.OVERFLOW;
                }
            };
        }
    }

    private static CharsetEncoder passthroughEncoder(Charset charset) {
        return new CharsetEncoder(charset, 1, 1) {
            @Override
            protected CoderResult encodeLoop(CharBuffer input, ByteBuffer output) {
                while (input.hasRemaining() && output.hasRemaining()) {
                    output.put((byte) input.get());
                }
                return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
            }
        };
    }
}
