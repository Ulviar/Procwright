/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Objects;

final class IncrementalTextDecoder {

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int MINIMUM_PENDING_BYTE_LIMIT = 64;

    private final CharsetPolicy policy;
    private final CharsetDecoder decoder;
    private final int pendingByteLimit;
    private final OutputWithoutInputGuard outputGuard;
    private byte[] pending = EMPTY_BYTES;
    private int pendingCount;
    private boolean malformed;
    private boolean ended;

    IncrementalTextDecoder(CharsetPolicy policy, int pendingByteLimit) {
        this(policy, pendingByteLimit, outputWithoutInputLimitFor(pendingByteLimit));
    }

    IncrementalTextDecoder(CharsetPolicy policy, int pendingByteLimit, int outputWithoutInputLimit) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (pendingByteLimit <= 0) {
            throw new IllegalArgumentException("pendingByteLimit must be positive");
        }
        this.pendingByteLimit = pendingByteLimit;
        this.outputGuard = new OutputWithoutInputGuard(outputWithoutInputLimit);
        this.decoder = policy.charset()
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    void decode(byte[] bytes, int count, Sink sink) throws CharacterCodingException {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(0, count, bytes.length);
        Objects.requireNonNull(sink, "sink");
        if (ended) {
            throw new IllegalStateException("decoder has reached end of input");
        }
        ByteBuffer input = sourceBuffer(bytes, count);
        StagedOutput staged = stagedOutputFor(decoder, input.remaining(), outputGuard.limit());
        try {
            decode(input, false, staged);
            retainPending(input);
            staged.publishTo(sink);
        } catch (DecoderStateException exception) {
            malformed = true;
            throw exception;
        }
    }

    void end(Sink sink) throws CharacterCodingException {
        Objects.requireNonNull(sink, "sink");
        if (ended) {
            return;
        }
        ended = true;
        ByteBuffer input = sourceBuffer(EMPTY_BYTES, 0);
        StagedOutput staged = stagedOutputFor(decoder, input.remaining(), outputGuard.limit());
        try {
            decode(input, true, staged);
            if (input.hasRemaining()) {
                throw stateFailure();
            }
            CharBuffer output = CharBuffer.allocate(128);
            while (true) {
                int outputPosition = output.position();
                CoderResult result = flushOutput(output);
                int outputCount = output.position() - outputPosition;
                recordProgress(result, false, outputCount);
                if (result.isOverflow()) {
                    emit(output, staged);
                    continue;
                }
                if (result.isError()) {
                    result.throwException();
                }
                if (result.isUnderflow()) {
                    emit(output, staged);
                    staged.publishTo(sink);
                    return;
                }
            }
        } catch (DecoderStateException exception) {
            malformed = true;
            throw exception;
        }
    }

    boolean malformed() {
        return malformed;
    }

    private void decode(ByteBuffer input, boolean endOfInput, Sink sink) throws CharacterCodingException {
        CharBuffer output = CharBuffer.allocate(128);
        while (true) {
            int inputPosition = input.position();
            int outputPosition = output.position();
            CoderResult result = decodeInput(input, output, endOfInput);
            int outputCount = output.position() - outputPosition;
            recordDecodeProgress(result, inputPosition, input.position(), outputCount);
            if (result.isOverflow()) {
                emit(output, sink);
                continue;
            }
            if (result.isUnderflow()) {
                emit(output, sink);
                return;
            }
            malformed = true;
            CodingErrorAction action =
                    result.isMalformed() ? policy.malformedInputAction() : policy.unmappableCharacterAction();
            if (action == CodingErrorAction.REPORT) {
                result.throwException();
            }
            int replacementEndPosition = replacementEndPosition(result, input);
            char[] replacement = decoder.replacement().toCharArray();
            emit(output, sink);
            sink.accept(replacement, replacement.length);
            input.position(replacementEndPosition);
            outputGuard.inputAdvanced();
        }
    }

    private static int replacementEndPosition(CoderResult result, ByteBuffer input) throws DecoderStateException {
        int errorLength = result.length();
        int remaining = input.remaining();
        long endPosition = (long) input.position() + errorLength;
        if (errorLength <= 0
                || errorLength > remaining
                || endPosition <= input.position()
                || endPosition > input.limit()) {
            throw new DecoderStateException("Decoder reported error length " + errorLength + " with only " + remaining
                    + " input bytes remaining");
        }
        return (int) endPosition;
    }

    private CoderResult decodeInput(ByteBuffer input, CharBuffer output, boolean endOfInput)
            throws DecoderStateException {
        try {
            return decoder.decode(input, output, endOfInput);
        } catch (RuntimeException | CoderMalfunctionError exception) {
            malformed = true;
            throw decoderFailure("decode", exception);
        }
    }

    private CoderResult flushOutput(CharBuffer output) throws DecoderStateException {
        try {
            return decoder.flush(output);
        } catch (RuntimeException | CoderMalfunctionError exception) {
            malformed = true;
            throw decoderFailure("flush", exception);
        }
    }

    static DecoderStateException decoderFailure(String operation, Throwable cause) {
        DecoderStateException failure = new DecoderStateException("Charset decoder failed during " + operation);
        failure.initCause(cause);
        return failure;
    }

    private ByteBuffer sourceBuffer(byte[] bytes, int count) {
        if (pendingCount == 0) {
            return ByteBuffer.wrap(bytes, 0, count);
        }
        if (count > Integer.MAX_VALUE - pendingCount) {
            throw new IllegalArgumentException("combined decoder input is too large");
        }
        ByteBuffer combined = ByteBuffer.allocate(pendingCount + count);
        combined.put(pending, 0, pendingCount);
        combined.put(bytes, 0, count);
        combined.flip();
        pending = EMPTY_BYTES;
        pendingCount = 0;
        return combined;
    }

    private void retainPending(ByteBuffer input) throws DecoderStateException {
        int remaining = input.remaining();
        if (remaining == 0) {
            pending = EMPTY_BYTES;
            pendingCount = 0;
            return;
        }
        if (remaining > pendingByteLimit) {
            malformed = true;
            throw stateFailure();
        }
        if (pending.length < remaining) {
            pending = new byte[remaining];
        }
        input.get(pending, 0, remaining);
        pendingCount = remaining;
    }

    static int pendingByteLimitFor(int configuredLimit) {
        if (configuredLimit <= 0) {
            throw new IllegalArgumentException("configuredLimit must be positive");
        }
        return Math.max(MINIMUM_PENDING_BYTE_LIMIT, configuredLimit);
    }

    static int outputWithoutInputLimitFor(int configuredLimit) {
        if (configuredLimit <= 0) {
            throw new IllegalArgumentException("configuredLimit must be positive");
        }
        return configuredLimit;
    }

    static StagedOutput stagedOutputFor(CharsetDecoder decoder, int inputBytes, int outputWithoutInputLimit) {
        Objects.requireNonNull(decoder, "decoder");
        if (inputBytes < 0) {
            throw new IllegalArgumentException("inputBytes must not be negative");
        }
        if (outputWithoutInputLimit <= 0) {
            throw new IllegalArgumentException("outputWithoutInputLimit must be positive");
        }
        double expectedOutput = Math.ceil(inputBytes * (double) Math.max(1.0f, decoder.maxCharsPerByte()));
        long boundedExpected = !Double.isFinite(expectedOutput) || expectedOutput >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (long) expectedOutput;
        long combinedLimit = boundedExpected + outputWithoutInputLimit;
        int limit = (int) Math.max(1, Math.min(Integer.MAX_VALUE, combinedLimit));
        return new StagedOutput(limit);
    }

    static StagedOutput stagedOutputFor(int characterLimit) {
        if (characterLimit <= 0) {
            throw new IllegalArgumentException("characterLimit must be positive");
        }
        return new StagedOutput(characterLimit);
    }

    static boolean inputAdvanced(int previousPosition, int newPosition) throws DecoderStateException {
        if (newPosition < previousPosition) {
            throw new DecoderStateException(
                    "Decoder moved input position backwards from " + previousPosition + " to " + newPosition);
        }
        return newPosition > previousPosition;
    }

    private void recordDecodeProgress(CoderResult result, int previousPosition, int newPosition, int outputCount)
            throws DecoderStateException {
        try {
            recordProgress(result, inputAdvanced(previousPosition, newPosition), outputCount);
        } catch (DecoderStateException exception) {
            malformed = true;
            throw exception;
        }
    }

    private void recordProgress(CoderResult result, boolean inputAdvanced, int outputCount)
            throws DecoderStateException {
        try {
            if (result.isOverflow() && !inputAdvanced && outputCount == 0) {
                throw new DecoderStateException(
                        "Decoder reported overflow without consuming input or producing output");
            }
            outputGuard.record(inputAdvanced, outputCount);
        } catch (DecoderStateException exception) {
            malformed = true;
            throw exception;
        }
    }

    private DecoderStateException stateFailure() {
        return new DecoderStateException("Decoder retained more than " + pendingByteLimit + " undecoded bytes");
    }

    private static void emit(CharBuffer output, Sink sink) throws CharacterCodingException {
        output.flip();
        int count = output.remaining();
        if (count > 0) {
            sink.accept(output.array(), count);
        }
        output.clear();
    }

    @FunctionalInterface
    interface Sink {

        void accept(char[] chars, int count) throws CharacterCodingException;
    }

    static final class StagedOutput implements Sink {

        private static final char[] EMPTY_CHARS = new char[0];

        private final int limit;
        private char[] chars = EMPTY_CHARS;
        private int length;

        private StagedOutput(int limit) {
            this.limit = limit;
        }

        @Override
        public void accept(char[] source, int count) throws DecoderStateException {
            Objects.requireNonNull(source, "source");
            Objects.checkFromIndexSize(0, count, source.length);
            if (count > limit - length) {
                throw new DecoderStateException(
                        "Decoder produced more than " + limit + " chars in one decode operation");
            }
            ensureCapacity(length + count);
            if (count > 0) {
                System.arraycopy(source, 0, chars, length, count);
                length += count;
            }
        }

        void publishTo(Sink sink) throws CharacterCodingException {
            Objects.requireNonNull(sink, "sink");
            if (length > 0) {
                sink.accept(chars, length);
            }
        }

        void appendTo(StringBuilder target) {
            Objects.requireNonNull(target, "target");
            target.append(chars, 0, length);
        }

        int length() {
            return length;
        }

        int remainingCapacity() {
            return limit - length;
        }

        private void ensureCapacity(int required) {
            if (required <= chars.length) {
                return;
            }
            int doubled = chars.length > limit - chars.length ? limit : chars.length * 2;
            int capacity = Math.max(required, Math.max(1, doubled));
            chars = Arrays.copyOf(chars, capacity);
        }
    }

    static final class DecoderStateException extends CharacterCodingException {

        private static final long serialVersionUID = 1L;

        private final String message;

        DecoderStateException(String message) {
            this.message = message;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }

    static final class OutputWithoutInputGuard {

        private final int limit;
        private long produced;

        OutputWithoutInputGuard(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("outputWithoutInputLimit must be positive");
            }
            this.limit = limit;
        }

        void record(boolean inputAdvanced, int outputCount) throws DecoderStateException {
            if (outputCount < 0) {
                throw new IllegalArgumentException("outputCount must not be negative");
            }
            if (inputAdvanced) {
                produced = 0;
                return;
            }
            if (outputCount > limit - produced) {
                throw new DecoderStateException(
                        "Decoder produced more than " + limit + " chars without consuming input");
            }
            produced += outputCount;
        }

        void inputAdvanced() {
            produced = 0;
        }

        int limit() {
            return limit;
        }
    }
}
