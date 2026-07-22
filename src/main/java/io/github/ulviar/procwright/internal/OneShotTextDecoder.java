/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.Objects;

final class OneShotTextDecoder {

    private static final int OUTPUT_CHUNK_CHARS = 1024;

    private OneShotTextDecoder() {}

    static String decode(byte[] bytes, CharsetPolicy policy) throws CharacterCodingException {
        Objects.requireNonNull(bytes, "bytes");
        CharsetDecoder decoder = configuredDecoder(policy);
        DecodeBudget budget = new DecodeBudget(maximumChars(decoder, bytes.length));
        ByteBuffer input = ByteBuffer.wrap(bytes);
        StringBuilder text = new StringBuilder(Math.min(budget.maximumChars(), OUTPUT_CHUNK_CHARS));
        CharBuffer output = CharBuffer.allocate(OUTPUT_CHUNK_CHARS);

        decode(decoder, input, output, true, budget, text);
        if (input.hasRemaining()) {
            throw contractFailure("Charset decoder reported end-of-input underflow before consuming all input");
        }
        flush(decoder, output, budget, text);
        return text.toString();
    }

    static int completePrefixLength(byte[] bytes, CharsetPolicy policy) throws CharacterCodingException {
        Objects.requireNonNull(bytes, "bytes");
        CharsetDecoder decoder = configuredDecoder(policy);
        DecodeBudget budget = new DecodeBudget(maximumChars(decoder, bytes.length));
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(OUTPUT_CHUNK_CHARS);

        decode(decoder, input, output, false, budget, null);
        return input.position();
    }

    private static CharsetDecoder configuredDecoder(CharsetPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return policy.charset()
                .newDecoder()
                .onMalformedInput(policy.malformedInputAction())
                .onUnmappableCharacter(policy.unmappableCharacterAction());
    }

    private static void decode(
            CharsetDecoder decoder,
            ByteBuffer input,
            CharBuffer output,
            boolean endOfInput,
            DecodeBudget budget,
            StringBuilder text)
            throws CharacterCodingException {
        while (true) {
            int inputBefore = input.position();
            output.clear();
            CoderResult result = decoder.decode(input, output, endOfInput);
            int produced = output.position();
            budget.record(inputBefore, input.position(), produced, result, "decode");
            if (result.isError()) {
                result.throwException();
            }
            append(text, output, produced);
            if (!result.isOverflow()) {
                return;
            }
        }
    }

    private static void flush(CharsetDecoder decoder, CharBuffer output, DecodeBudget budget, StringBuilder text)
            throws CharacterCodingException {
        while (true) {
            output.clear();
            CoderResult result = decoder.flush(output);
            int produced = output.position();
            budget.record(0, 0, produced, result, "flush");
            if (result.isError()) {
                result.throwException();
            }
            append(text, output, produced);
            if (!result.isOverflow()) {
                return;
            }
        }
    }

    private static void append(StringBuilder text, CharBuffer output, int produced) {
        if (text != null && produced > 0) {
            text.append(output.array(), 0, produced);
        }
    }

    private static int maximumChars(CharsetDecoder decoder, int inputBytes) {
        double maximum = Math.ceil(inputBytes * (double) decoder.maxCharsPerByte());
        if (!Double.isFinite(maximum) || maximum > Integer.MAX_VALUE - 8L) {
            throw contractFailure("Charset decoder declares an unsupported output expansion");
        }
        return (int) maximum;
    }

    private static IllegalStateException contractFailure(String message) {
        return new IllegalStateException(message);
    }

    private static final class DecodeBudget {

        private final int maximumChars;
        private int producedChars;

        private DecodeBudget(int maximumChars) {
            this.maximumChars = maximumChars;
        }

        private int maximumChars() {
            return maximumChars;
        }

        private void record(int inputBefore, int inputAfter, int produced, CoderResult result, String operation) {
            if (inputAfter < inputBefore) {
                throw contractFailure("Charset decoder moved input position backwards during " + operation);
            }
            if (produced < 0 || producedChars > maximumChars - produced) {
                throw contractFailure("Charset decoder exceeded its declared output bound during " + operation);
            }
            producedChars += produced;
            if (result.isOverflow() && inputAfter == inputBefore && produced == 0) {
                throw contractFailure("Charset decoder reported overflow without progress during " + operation);
            }
        }
    }
}
