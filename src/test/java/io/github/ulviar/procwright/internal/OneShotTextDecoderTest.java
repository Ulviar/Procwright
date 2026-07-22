/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class OneShotTextDecoderTest {

    @Test
    void completePrefixExcludesTrailingPartialCodePoint() throws Exception {
        byte[] truncatedEuro = {(byte) 0xE2, (byte) 0x82};

        int completePrefix =
                OneShotTextDecoder.completePrefixLength(truncatedEuro, CharsetPolicy.report(StandardCharsets.UTF_8));

        assertEquals(0, completePrefix);
    }

    @Test
    void decodeAcceptsValidTextWithinDeclaredExpansion() throws Exception {
        byte[] encoded = "hello".getBytes(StandardCharsets.UTF_8);

        String decoded = OneShotTextDecoder.decode(encoded, CharsetPolicy.report(StandardCharsets.UTF_8));

        assertEquals("hello", decoded);
    }

    @Test
    void completePrefixRejectsOverflowWithoutProgress() {
        Charset charset = new ContractViolatingCharset(false);

        assertThrows(
                IllegalStateException.class,
                () -> OneShotTextDecoder.completePrefixLength(new byte[] {'a'}, CharsetPolicy.report(charset)));
    }

    @Test
    void completePrefixBoundsOutputWithoutInputConsumption() {
        Charset charset = new ContractViolatingCharset(true);

        assertThrows(
                IllegalStateException.class,
                () -> OneShotTextDecoder.completePrefixLength(new byte[] {'a'}, CharsetPolicy.report(charset)));
    }

    private static final class ContractViolatingCharset extends Charset {

        private final boolean produceOutput;

        private ContractViolatingCharset(boolean produceOutput) {
            super(produceOutput ? "x-procwright-unit-output-overflow" : "x-procwright-unit-no-progress", null);
            this.produceOutput = produceOutput;
        }

        @Override
        public boolean contains(Charset charset) {
            return charset == this;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (produceOutput) {
                        output.put('x');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }
}
