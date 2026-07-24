/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Objects;

/**
 * Decodes independent, byte-length-delimited protocol text fields.
 *
 * <p>Each field gets a fresh charset decoder. Local field limits and the response-wide character budget are charged
 * as output is accepted, including the first character that proves a limit was exceeded. The byte source retains
 * ownership of capability, deadline, terminal ordering, and response-wide byte accounting.
 */
final class ProtocolTextFieldDecoder {

    private static final int INPUT_BUFFER_SIZE = 8192;
    private static final int OUTPUT_BUFFER_SIZE = 8192;

    private final CharsetPolicy charsetPolicy;
    private final ProtocolResponseBudget budget;
    private final ProtocolRuntimeFailures failures;
    private final ByteSource input;

    ProtocolTextFieldDecoder(
            CharsetPolicy charsetPolicy,
            ProtocolResponseBudget budget,
            ProtocolRuntimeFailures failures,
            ByteSource input) {
        this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.input = Objects.requireNonNull(input, "input");
    }

    String decode(int byteLength, int characterLimit) {
        if (byteLength <= 0) {
            throw new IllegalArgumentException("byteLength must be positive");
        }
        if (characterLimit <= 0) {
            throw new IllegalArgumentException("characterLimit must be positive");
        }

        CharsetDecoder decoder = newDecoder();
        ByteBuffer inputBuffer = ByteBuffer.allocate(Math.min(byteLength, INPUT_BUFFER_SIZE));
        int initialEffectiveLimit = Math.min(characterLimit, budget.remainingChars());
        CharBuffer output = CharBuffer.allocate(outputCapacity(initialEffectiveLimit));
        BoundedText text =
                new BoundedText(characterLimit, initialCharacterCapacity(decoder, byteLength, initialEffectiveLimit));
        int unreadBytes = byteLength;
        try {
            while (true) {
                if (unreadBytes > 0) {
                    if (!inputBuffer.hasRemaining()) {
                        inputBuffer = growInput(inputBuffer, byteLength);
                    }
                    int requested = Math.min(
                            Math.min(inputBuffer.remaining(), unreadBytes), firstExcessLimit(effectiveRemaining(text)));
                    int count = input.read(inputBuffer.array(), inputBuffer.position(), requested);
                    if (count <= 0 || count > requested) {
                        throw new IncrementalTextDecoder.DecoderStateException(
                                "Protocol text field source returned invalid byte count " + count);
                    }
                    inputBuffer.position(inputBuffer.position() + count);
                    unreadBytes -= count;
                }

                boolean endOfInput = unreadBytes == 0;
                inputBuffer.flip();
                decodeInput(decoder, inputBuffer, output, endOfInput, text);

                if (endOfInput) {
                    if (inputBuffer.hasRemaining()) {
                        throw new IncrementalTextDecoder.DecoderStateException(
                                "Decoder retained input after the complete text field ended");
                    }
                    break;
                }
                inputBuffer.compact();
            }
            flush(decoder, output, text);
        } catch (TextTooLargeException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                    "Protocol response text exceeds maxChars",
                    null);
        } catch (CharacterCodingException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR,
                    "Could not decode complete protocol response field",
                    exception);
        }
        return text.text();
    }

    private CharsetDecoder newDecoder() {
        try {
            return charsetPolicy
                    .charset()
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
        } catch (RuntimeException | CoderMalfunctionError exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR,
                    "Could not initialize complete protocol response field decoder",
                    exception);
        }
    }

    private void decodeInput(
            CharsetDecoder decoder, ByteBuffer inputBuffer, CharBuffer output, boolean endOfInput, BoundedText text)
            throws CharacterCodingException {
        while (true) {
            int remainingChars = effectiveRemaining(text);
            prepareOutput(output, remainingChars);
            int previousInputPosition = inputBuffer.position();
            int previousOutputPosition = output.position();
            CoderResult result = decode(decoder, inputBuffer, output, endOfInput);
            boolean inputAdvanced = IncrementalTextDecoder.inputAdvanced(previousInputPosition, inputBuffer.position());
            int outputCount = output.position() - previousOutputPosition;
            rejectBudgetLimitedOverflow(result, inputAdvanced, outputCount, output, remainingChars);
            ensureProgress(result, inputAdvanced, outputCount);
            appendOutput(output, text);
            if (result.isOverflow()) {
                continue;
            }
            if (result.isError()) {
                replaceError(decoder, result, inputBuffer, text);
                continue;
            }
            return;
        }
    }

    private static CoderResult decode(
            CharsetDecoder decoder, ByteBuffer inputBuffer, CharBuffer output, boolean endOfInput)
            throws IncrementalTextDecoder.DecoderStateException {
        try {
            return decoder.decode(inputBuffer, output, endOfInput);
        } catch (RuntimeException | CoderMalfunctionError exception) {
            throw IncrementalTextDecoder.decoderFailure("decode", exception);
        }
    }

    private void flush(CharsetDecoder decoder, CharBuffer output, BoundedText text) throws CharacterCodingException {
        while (true) {
            int remainingChars = effectiveRemaining(text);
            prepareOutput(output, remainingChars);
            int previousOutputPosition = output.position();
            CoderResult result;
            try {
                result = decoder.flush(output);
            } catch (RuntimeException | CoderMalfunctionError exception) {
                throw IncrementalTextDecoder.decoderFailure("flush", exception);
            }
            int outputCount = output.position() - previousOutputPosition;
            rejectBudgetLimitedOverflow(result, false, outputCount, output, remainingChars);
            ensureProgress(result, false, outputCount);
            appendOutput(output, text);
            if (result.isError()) {
                result.throwException();
            }
            if (!result.isOverflow()) {
                return;
            }
        }
    }

    private void replaceError(CharsetDecoder decoder, CoderResult result, ByteBuffer inputBuffer, BoundedText text)
            throws CharacterCodingException {
        CodingErrorAction action =
                result.isMalformed() ? charsetPolicy.malformedInputAction() : charsetPolicy.unmappableCharacterAction();
        if (action == CodingErrorAction.REPORT) {
            result.throwException();
        }
        int replacementEndPosition = replacementEndPosition(result, inputBuffer);
        String replacement = decoder.replacement();
        int count = Math.min(replacement.length(), firstExcessLimit(effectiveRemaining(text)));
        budget.addChars(count);
        text.append(replacement, count);
        inputBuffer.position(replacementEndPosition);
    }

    private void appendOutput(CharBuffer output, BoundedText text) throws TextTooLargeException {
        output.flip();
        int count = output.remaining();
        if (count > 0) {
            budget.addChars(count);
            text.append(output);
        }
        output.clear();
    }

    private int effectiveRemaining(BoundedText text) {
        return Math.min(text.remaining(), budget.remainingChars());
    }

    private static void prepareOutput(CharBuffer output, int remainingChars) {
        output.clear();
        output.limit(Math.min(output.capacity(), firstExcessLimit(remainingChars)));
    }

    private void rejectBudgetLimitedOverflow(
            CoderResult result, boolean inputAdvanced, int outputCount, CharBuffer output, int remainingChars)
            throws TextTooLargeException {
        int firstExcess = firstExcessLimit(remainingChars);
        if (result.isOverflow() && !inputAdvanced && outputCount == 0 && firstExcess <= output.capacity()) {
            budget.addChars(firstExcess);
            throw new TextTooLargeException();
        }
    }

    private static int replacementEndPosition(CoderResult result, ByteBuffer inputBuffer)
            throws IncrementalTextDecoder.DecoderStateException {
        int errorLength = result.length();
        int remaining = inputBuffer.remaining();
        long endPosition = (long) inputBuffer.position() + errorLength;
        if (errorLength <= 0
                || errorLength > remaining
                || endPosition <= inputBuffer.position()
                || endPosition > inputBuffer.limit()) {
            throw new IncrementalTextDecoder.DecoderStateException("Decoder reported error length " + errorLength
                    + " with only " + remaining + " input bytes remaining");
        }
        return (int) endPosition;
    }

    private static void ensureProgress(CoderResult result, boolean inputAdvanced, int outputCount)
            throws IncrementalTextDecoder.DecoderStateException {
        if (result.isOverflow() && !inputAdvanced && outputCount == 0) {
            throw new IncrementalTextDecoder.DecoderStateException(
                    "Decoder reported overflow without consuming input or producing output");
        }
    }

    private static ByteBuffer growInput(ByteBuffer inputBuffer, int byteLength)
            throws IncrementalTextDecoder.DecoderStateException {
        if (inputBuffer.capacity() >= byteLength) {
            throw new IncrementalTextDecoder.DecoderStateException(
                    "Decoder retained all declared field bytes without consuming input");
        }
        int growth = Math.max(1, inputBuffer.capacity());
        int grownCapacity = inputBuffer.capacity() > byteLength - growth ? byteLength : inputBuffer.capacity() + growth;
        inputBuffer.flip();
        ByteBuffer grown = ByteBuffer.allocate(grownCapacity);
        grown.put(inputBuffer);
        return grown;
    }

    private static int outputCapacity(int characterLimit) {
        return Math.min(OUTPUT_BUFFER_SIZE, firstExcessLimit(characterLimit));
    }

    private static int firstExcessLimit(int remainingChars) {
        return remainingChars == Integer.MAX_VALUE ? Integer.MAX_VALUE : remainingChars + 1;
    }

    private static int initialCharacterCapacity(CharsetDecoder decoder, int byteLength, int characterLimit) {
        int initialLimit = Math.min(OUTPUT_BUFFER_SIZE, characterLimit);
        double expected = Math.ceil(byteLength * (double) decoder.maxCharsPerByte());
        if (!Double.isFinite(expected) || expected >= initialLimit) {
            return initialLimit;
        }
        return (int) expected;
    }

    @FunctionalInterface
    interface ByteSource {

        int read(byte[] buffer, int offset, int length);
    }

    private static final class TextTooLargeException extends CharacterCodingException {

        private static final long serialVersionUID = 1L;
    }

    private static final class BoundedText {

        private static final char[] EMPTY = new char[0];

        private final int limit;
        private char[] chars;
        private int length;

        private BoundedText(int limit, int initialCapacity) {
            this.limit = limit;
            chars = initialCapacity == 0 ? EMPTY : new char[initialCapacity];
        }

        private void append(CharBuffer output) throws TextTooLargeException {
            int count = output.remaining();
            if (count > limit - length) {
                throw new TextTooLargeException();
            }
            ensureCapacity(length + count);
            output.get(chars, length, count);
            length += count;
        }

        private void append(String value, int count) throws TextTooLargeException {
            if (count > limit - length) {
                throw new TextTooLargeException();
            }
            ensureCapacity(length + count);
            value.getChars(0, count, chars, length);
            length += count;
        }

        private int remaining() {
            return limit - length;
        }

        private void ensureCapacity(int required) {
            if (required <= chars.length) {
                return;
            }
            int doubled = chars.length > limit - chars.length ? limit : chars.length * 2;
            int capacity = Math.min(limit, Math.max(required, Math.max(1, doubled)));
            chars = Arrays.copyOf(chars, capacity);
        }

        private String text() {
            return new String(chars, 0, length);
        }
    }
}
