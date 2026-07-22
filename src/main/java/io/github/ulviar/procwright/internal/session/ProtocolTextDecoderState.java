/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.util.Objects;
import java.util.function.IntFunction;

/** Carries incremental decoding and bounded decoded-line output across readers of one process output stream. */
final class ProtocolTextDecoderState {

    private static final char[] EMPTY_CHARS = new char[0];
    private static final int INPUT_WINDOW_SIZE = 8192;
    private static final int MAX_RETAINED_STAGING_CHARS = 8192;

    private final CharsetDecoder decoder;
    private final int pendingByteLimit;
    private final int outputWithoutInputLimit;
    private final IncrementalTextDecoder.OutputWithoutInputGuard outputGuard;
    private final StagedText staged;
    private final DecodedLineSuffix decodedLineSuffix;
    private ByteBuffer input;
    private byte[] inputWindow;
    private final CharBuffer output = CharBuffer.allocate(128);
    private boolean pendingInput;
    private boolean finished;

    ProtocolTextDecoderState(CharsetPolicy policy) {
        this(policy, 1024 * 1024, 1024 * 1024);
    }

    ProtocolTextDecoderState(CharsetPolicy policy, int pendingByteLimit) {
        this(policy, pendingByteLimit, IncrementalTextDecoder.outputWithoutInputLimitFor(pendingByteLimit));
    }

    ProtocolTextDecoderState(CharsetPolicy policy, int pendingByteLimit, int outputWithoutInputLimit) {
        this(
                policy,
                pendingByteLimit,
                outputWithoutInputLimit,
                Math.min(pendingByteLimit, outputWithoutInputLimit),
                char[]::new);
    }

    ProtocolTextDecoderState(
            CharsetPolicy policy,
            int pendingByteLimit,
            int outputWithoutInputLimit,
            IntFunction<char[]> stagingAllocator) {
        this(
                policy,
                pendingByteLimit,
                outputWithoutInputLimit,
                Math.min(pendingByteLimit, outputWithoutInputLimit),
                stagingAllocator);
    }

    ProtocolTextDecoderState(
            CharsetPolicy policy, int pendingByteLimit, int outputWithoutInputLimit, int decodedLineSuffixLimit) {
        this(policy, pendingByteLimit, outputWithoutInputLimit, decodedLineSuffixLimit, char[]::new);
    }

    private ProtocolTextDecoderState(
            CharsetPolicy policy,
            int pendingByteLimit,
            int outputWithoutInputLimit,
            int decodedLineSuffixLimit,
            IntFunction<char[]> stagingAllocator) {
        Objects.requireNonNull(policy, "policy");
        if (pendingByteLimit <= 0) {
            throw new IllegalArgumentException("pendingByteLimit must be positive");
        }
        if (decodedLineSuffixLimit <= 0) {
            throw new IllegalArgumentException("decodedLineSuffixLimit must be positive");
        }
        this.pendingByteLimit = pendingByteLimit;
        this.outputWithoutInputLimit = outputWithoutInputLimit;
        this.outputGuard = new IncrementalTextDecoder.OutputWithoutInputGuard(outputWithoutInputLimit);
        this.staged = new StagedText(stagingAllocator);
        this.decodedLineSuffix = new DecodedLineSuffix(decodedLineSuffixLimit);
        input = ByteBuffer.allocate(Math.min(16, pendingByteLimit));
        decoder = policy.charset()
                .newDecoder()
                .onMalformedInput(policy.malformedInputAction())
                .onUnmappableCharacter(policy.unmappableCharacterAction());
    }

    void decode(byte value, StringBuilder target) throws CharacterCodingException {
        decode(value, target, 0);
    }

    void decode(byte value, StringBuilder target, int characterLimit) throws CharacterCodingException {
        Objects.requireNonNull(target, "target");
        decodeTo(value, (chars, count) -> target.append(chars, 0, count), characterLimit);
    }

    void decodeTo(byte value, IncrementalTextDecoder.Sink target, int characterLimit) throws CharacterCodingException {
        Objects.requireNonNull(target, "target");
        if (characterLimit < 0) {
            throw new IllegalArgumentException("characterLimit must not be negative");
        }
        if (finished) {
            throw new IllegalStateException("protocol text decoder is finished");
        }
        if (!input.hasRemaining()) {
            growInput();
        }
        input.put(value);
        input.flip();
        staged.reset(characterLimit == 0 ? unboundedStagingLimit(input.remaining()) : characterLimit);
        try {
            while (true) {
                prepareOutput(staged, characterLimit > 0);
                int inputPosition = input.position();
                int outputPosition = output.position();
                CoderResult result = decodeInput(false);
                int outputCount = output.position() - outputPosition;
                boolean inputAdvanced = IncrementalTextDecoder.inputAdvanced(inputPosition, input.position());
                rejectBudgetLimitedOverflow(result, inputAdvanced, outputCount, staged, characterLimit > 0);
                recordProgress(result, inputAdvanced, outputCount);
                if (result.isError()) {
                    result.throwException();
                }
                appendOutput(staged);
                if (result.isOverflow()) {
                    continue;
                }
                input.compact();
                pendingInput = input.position() > 0;
                staged.publishTo(target);
                return;
            }
        } finally {
            staged.finishOperation();
        }
    }

    void finish(StringBuilder target) throws CharacterCodingException {
        finish(target, 0);
    }

    void finish(StringBuilder target, int characterLimit) throws CharacterCodingException {
        Objects.requireNonNull(target, "target");
        finishTo((chars, count) -> target.append(chars, 0, count), characterLimit);
    }

    void finishTo(IncrementalTextDecoder.Sink target, int characterLimit) throws CharacterCodingException {
        Objects.requireNonNull(target, "target");
        if (characterLimit < 0) {
            throw new IllegalArgumentException("characterLimit must not be negative");
        }
        if (finished) {
            return;
        }
        finished = true;
        input.flip();
        staged.reset(characterLimit == 0 ? unboundedStagingLimit(input.remaining()) : characterLimit);
        try {
            while (true) {
                prepareOutput(staged, characterLimit > 0);
                int inputPosition = input.position();
                int outputPosition = output.position();
                CoderResult result = decodeInput(true);
                int outputCount = output.position() - outputPosition;
                boolean inputAdvanced = IncrementalTextDecoder.inputAdvanced(inputPosition, input.position());
                rejectBudgetLimitedOverflow(result, inputAdvanced, outputCount, staged, characterLimit > 0);
                recordProgress(result, inputAdvanced, outputCount);
                if (result.isError()) {
                    result.throwException();
                }
                appendOutput(staged);
                if (result.isOverflow()) {
                    continue;
                }
                break;
            }
            if (input.hasRemaining()) {
                throw stateFailure();
            }
            pendingInput = false;
            while (true) {
                prepareOutput(staged, characterLimit > 0);
                int outputPosition = output.position();
                CoderResult result = flushOutput();
                int outputCount = output.position() - outputPosition;
                rejectBudgetLimitedOverflow(result, false, outputCount, staged, characterLimit > 0);
                recordProgress(result, false, outputCount);
                if (result.isError()) {
                    result.throwException();
                }
                appendOutput(staged);
                if (result.isOverflow()) {
                    continue;
                }
                staged.publishTo(target);
                return;
            }
        } finally {
            staged.finishOperation();
        }
    }

    boolean hasPendingInput() {
        return pendingInput;
    }

    boolean hasPendingLineOutput() {
        return decodedLineSuffix.hasPending();
    }

    int remainingLineOutputCapacity() {
        return decodedLineSuffix.remainingCapacity();
    }

    int lineOutputCheckpoint() {
        return decodedLineSuffix.pendingCount();
    }

    void rollbackLineOutput(int checkpoint) {
        decodedLineSuffix.truncate(checkpoint);
    }

    void appendLineOutput(char[] source, int offset, int count) throws DecodedLineSuffixLimitExceededException {
        decodedLineSuffix.append(source, offset, count);
    }

    int copyFirstLineOutput(StringBuilder target) {
        return decodedLineSuffix.copyFirstLineTo(target);
    }

    void consumeLineOutput(int count) {
        decodedLineSuffix.consume(count);
    }

    byte[] inputWindow() {
        if (inputWindow == null) {
            inputWindow = new byte[Math.min(INPUT_WINDOW_SIZE, pendingByteLimit)];
        }
        return inputWindow;
    }

    private CoderResult decodeInput(boolean endOfInput) throws IncrementalTextDecoder.DecoderStateException {
        try {
            return decoder.decode(input, output, endOfInput);
        } catch (RuntimeException | CoderMalfunctionError exception) {
            throw IncrementalTextDecoder.decoderFailure("decode", exception);
        }
    }

    private CoderResult flushOutput() throws IncrementalTextDecoder.DecoderStateException {
        try {
            return decoder.flush(output);
        } catch (RuntimeException | CoderMalfunctionError exception) {
            throw IncrementalTextDecoder.decoderFailure("flush", exception);
        }
    }

    private void appendOutput(StagedText staged) throws CharacterCodingException {
        output.flip();
        staged.accept(output.array(), output.remaining());
        output.clear();
    }

    private void prepareOutput(StagedText staged, boolean bounded) throws OutputLimitExceededException {
        if (!bounded) {
            output.limit(output.capacity());
            return;
        }
        int remaining = staged.remainingCapacity();
        if (remaining == 0) {
            throw new OutputLimitExceededException(staged.length());
        }
        output.limit(Math.min(output.capacity(), remaining));
    }

    private void rejectBudgetLimitedOverflow(
            CoderResult result, boolean inputAdvanced, int outputCount, StagedText staged, boolean bounded)
            throws OutputLimitExceededException {
        if (bounded
                && result.isOverflow()
                && !inputAdvanced
                && outputCount == 0
                && staged.remainingCapacity() <= output.capacity()) {
            throw new OutputLimitExceededException(staged.length() + staged.remainingCapacity());
        }
    }

    private void growInput() throws IncrementalTextDecoder.DecoderStateException {
        if (input.capacity() >= pendingByteLimit) {
            throw stateFailure();
        }
        input.flip();
        int growth = Math.max(1, input.capacity());
        int grownCapacity = input.capacity() > pendingByteLimit - growth ? pendingByteLimit : input.capacity() + growth;
        ByteBuffer grown = ByteBuffer.allocate(grownCapacity);
        grown.put(input);
        input = grown;
    }

    private void recordProgress(CoderResult result, boolean inputAdvanced, int outputCount)
            throws IncrementalTextDecoder.DecoderStateException {
        if (result.isOverflow() && !inputAdvanced && outputCount == 0) {
            throw new IncrementalTextDecoder.DecoderStateException(
                    "Decoder reported overflow without consuming input or producing output");
        }
        outputGuard.record(inputAdvanced, outputCount);
    }

    private IncrementalTextDecoder.DecoderStateException stateFailure() {
        return new IncrementalTextDecoder.DecoderStateException(
                "Decoder retained more than " + pendingByteLimit + " undecoded bytes");
    }

    private int unboundedStagingLimit(int inputBytes) {
        double expectedOutput = Math.ceil(inputBytes * (double) Math.max(1.0f, decoder.maxCharsPerByte()));
        long boundedExpected = !Double.isFinite(expectedOutput) || expectedOutput >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (long) expectedOutput;
        long combinedLimit = boundedExpected + outputWithoutInputLimit;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, combinedLimit));
    }

    private static final class DecodedLineSuffix {

        private static final char[] EMPTY = new char[0];

        private final int limit;
        private char[] chars = EMPTY;
        private int start;
        private int end;

        private DecodedLineSuffix(int limit) {
            this.limit = limit;
        }

        private synchronized void append(char[] source, int offset, int count)
                throws DecodedLineSuffixLimitExceededException {
            Objects.checkFromIndexSize(offset, count, source.length);
            if (count == 0) {
                return;
            }
            if (count > remainingCapacity()) {
                throw new DecodedLineSuffixLimitExceededException(limit);
            }
            ensureCapacity(count);
            System.arraycopy(source, offset, chars, end, count);
            end += count;
        }

        private synchronized int copyFirstLineTo(StringBuilder target) {
            Objects.requireNonNull(target, "target");
            int count = pendingCount();
            for (int index = start; index < end; index++) {
                if (chars[index] == '\n') {
                    count = index - start + 1;
                    break;
                }
            }
            target.append(chars, start, count);
            return count;
        }

        private synchronized void consume(int count) {
            if (count < 0 || count > pendingCount()) {
                throw new IllegalArgumentException("count exceeds pending decoded line output");
            }
            start += count;
            clearIfEmpty();
        }

        private synchronized void truncate(int pendingCount) {
            if (pendingCount < 0 || pendingCount > pendingCount()) {
                throw new IllegalArgumentException("checkpoint exceeds pending decoded line output");
            }
            end = start + pendingCount;
            clearIfEmpty();
        }

        private synchronized boolean hasPending() {
            return start < end;
        }

        private synchronized int remainingCapacity() {
            return limit - pendingCount();
        }

        private synchronized int pendingCount() {
            return end - start;
        }

        private void ensureCapacity(int additional) {
            int length = pendingCount();
            int required = length + additional;
            if (required <= chars.length) {
                if (start > 0 && additional > chars.length - end) {
                    System.arraycopy(chars, start, chars, 0, length);
                    start = 0;
                    end = length;
                }
                return;
            }
            int doubled = chars.length > limit - chars.length ? limit : chars.length * 2;
            int capacity = Math.min(limit, Math.max(required, Math.max(1, doubled)));
            char[] grown = new char[capacity];
            System.arraycopy(chars, start, grown, 0, length);
            chars = grown;
            start = 0;
            end = length;
        }

        private void clearIfEmpty() {
            if (start == end) {
                start = 0;
                end = 0;
            }
        }
    }

    private static final class StagedText implements IncrementalTextDecoder.Sink {

        private final IntFunction<char[]> allocator;
        private char[] chars = EMPTY_CHARS;
        private int limit;
        private int length;

        private StagedText(IntFunction<char[]> allocator) {
            this.allocator = Objects.requireNonNull(allocator, "allocator");
        }

        private void reset(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
            length = 0;
        }

        @Override
        public void accept(char[] source, int count) throws IncrementalTextDecoder.DecoderStateException {
            Objects.requireNonNull(source, "source");
            Objects.checkFromIndexSize(0, count, source.length);
            if (count > limit - length) {
                throw new IncrementalTextDecoder.DecoderStateException(
                        "Decoder produced more than " + limit + " chars in one decode operation");
            }
            ensureCapacity(length + count);
            if (count > 0) {
                System.arraycopy(source, 0, chars, length, count);
                length += count;
            }
        }

        private void publishTo(IncrementalTextDecoder.Sink target) throws CharacterCodingException {
            if (length > 0) {
                target.accept(chars, length);
            }
        }

        private int length() {
            return length;
        }

        private int remainingCapacity() {
            return limit - length;
        }

        private void finishOperation() {
            length = 0;
            if (chars.length > MAX_RETAINED_STAGING_CHARS) {
                chars = EMPTY_CHARS;
            }
        }

        private void ensureCapacity(int required) throws IncrementalTextDecoder.DecoderStateException {
            if (required <= chars.length) {
                return;
            }
            int doubled = chars.length > limit - chars.length ? limit : chars.length * 2;
            int capacity = Math.max(required, Math.max(1, doubled));
            char[] grown = Objects.requireNonNull(allocator.apply(capacity), "stagingAllocator returned null");
            if (grown.length < capacity) {
                throw new IncrementalTextDecoder.DecoderStateException(
                        "Staging allocator returned " + grown.length + " chars for capacity " + capacity);
            }
            System.arraycopy(chars, 0, grown, 0, length);
            chars = grown;
        }
    }

    static final class OutputLimitExceededException extends CharacterCodingException {

        private static final long serialVersionUID = 1L;

        private final int decodedChars;

        private OutputLimitExceededException(int decodedChars) {
            this.decodedChars = decodedChars;
        }

        int decodedChars() {
            return decodedChars;
        }

        @Override
        public String getMessage() {
            return "Decoder output exceeds the current character budget";
        }
    }

    static final class DecodedLineSuffixLimitExceededException extends CharacterCodingException {

        private static final long serialVersionUID = 1L;

        private final int limit;

        private DecodedLineSuffixLimitExceededException(int limit) {
            this.limit = limit;
        }

        int limit() {
            return limit;
        }

        @Override
        public String getMessage() {
            return "Decoded line suffix exceeds its " + limit + " char limit";
        }
    }
}
