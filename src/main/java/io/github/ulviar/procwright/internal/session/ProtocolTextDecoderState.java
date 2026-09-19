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

/** Carries incremental charset-decoder state across readers of one process output stream. */
final class ProtocolTextDecoderState {

    private final CharsetDecoder decoder;
    private final int pendingByteLimit;
    private final int outputWithoutInputLimit;
    private final IncrementalTextDecoder.OutputWithoutInputGuard outputGuard;
    private final BoundedCharacterStaging staged;
    private ByteBuffer input;
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
        this(policy, pendingByteLimit, outputWithoutInputLimit, char[]::new);
    }

    ProtocolTextDecoderState(
            CharsetPolicy policy,
            int pendingByteLimit,
            int outputWithoutInputLimit,
            IntFunction<char[]> stagingAllocator) {
        Objects.requireNonNull(policy, "policy");
        if (pendingByteLimit <= 0) {
            throw new IllegalArgumentException("pendingByteLimit must be positive");
        }
        this.pendingByteLimit = pendingByteLimit;
        this.outputWithoutInputLimit = outputWithoutInputLimit;
        this.outputGuard = new IncrementalTextDecoder.OutputWithoutInputGuard(outputWithoutInputLimit);
        this.staged = new BoundedCharacterStaging(stagingAllocator);
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

    private void appendOutput(BoundedCharacterStaging staged) throws CharacterCodingException {
        output.flip();
        staged.accept(output.array(), output.remaining());
        output.clear();
    }

    private void prepareOutput(BoundedCharacterStaging staged, boolean bounded) throws OutputLimitExceededException {
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
            CoderResult result, boolean inputAdvanced, int outputCount, BoundedCharacterStaging staged, boolean bounded)
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
        return IncrementalTextDecoder.stagingCharacterLimit(decoder, inputBytes, outputWithoutInputLimit);
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
}
