/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.nio.charset.CharacterCodingException;
import java.util.Objects;

/** Owns complete-field and continuous text decoding for one protocol response reader. */
final class ProtocolTextReader {

    private static final int INPUT_WINDOW_SIZE = 8192;

    private final ProtocolOutputQueue output;
    private final long deadlineNanos;
    private final ProtocolResponseBudget budget;
    private final ProtocolTextDecoderState decoder;
    private final DecodedLineBuffer decodedLines;
    private final StreamState streamState;
    private final ProtocolRuntimeFailures failures;
    private final ProtocolReadSource source;
    private final CharsetPolicy charsetPolicy;
    private final ProtocolOutputQueue.ReadWindow transaction = new ProtocolOutputQueue.ReadWindow();
    private ProtocolTextFieldDecoder textFields;

    ProtocolTextReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            long deadlineNanos,
            ProtocolResponseBudget budget,
            StreamState streamState,
            ProtocolRuntimeFailures failures,
            ProtocolReadSource source) {
        this.output = Objects.requireNonNull(output, "output");
        charsetPolicy = Objects.requireNonNull(options, "options").charsetPolicy();
        this.deadlineNanos = deadlineNanos;
        this.budget = Objects.requireNonNull(budget, "budget");
        StreamState stream = Objects.requireNonNull(streamState, "streamState");
        this.streamState = stream;
        decoder = stream.decoder;
        decodedLines = stream.decodedLines;
        this.failures = Objects.requireNonNull(failures, "failures");
        this.source = Objects.requireNonNull(source, "source");
    }

    String readTextExactly(int byteLength, int maxChars) {
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must not be negative");
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (byteLength == 0) {
            source.verifyAccess();
            return "";
        }
        checkNonLineReadPreconditions();
        ensureRawReadAllowed();
        budget.ensureCharacterBudgetOpen();
        budget.ensureBytesAvailable(byteLength);
        return textFields().decode(byteLength, maxChars);
    }

    String readLine(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        source.checkLineReadPreconditions(decodedLines.hasPending());
        budget.ensureCharacterBudgetOpen();
        int readLimit = maxChars >= Integer.MAX_VALUE - 2 ? Integer.MAX_VALUE : maxChars + 2;
        String line = readContinuousTextUntil((byte) '\n', readLimit, true, maxChars);
        if (line.endsWith("\n")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line;
    }

    String readTextUntil(byte delimiter, int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        checkNonLineReadPreconditions();
        budget.ensureCharacterBudgetOpen();
        return readContinuousTextUntil(delimiter, maxChars, false, -1);
    }

    void ensureNonLineReadAllowed() {
        if (decodedLines.hasPending()) {
            throw failures.failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED,
                    "Cannot use a non-line reader while decoded line output remains pending",
                    null);
        }
    }

    void ensureRawReadAllowed() {
        if (decoder.hasPendingInput() || decodedLines.hasPending()) {
            throw failures.failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED,
                    "Cannot start a raw or complete-field read while continuous text decoding has pending input or"
                            + " output",
                    null);
        }
    }

    private void decodeTextByte(byte value, StringBuilder text, int maxChars) {
        int previousLength = text.length();
        try {
            decoder.decode(value, text, currentTextDecodeLimit(text, maxChars));
        } catch (ProtocolTextDecoderState.OutputLimitExceededException exception) {
            chargeContinuousLimitFailure(exception);
        } catch (CharacterCodingException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", exception);
        }
        int decodedChars = text.length() - previousLength;
        if (decodedChars > 0) {
            budget.addChars(decodedChars);
        }
        enforceTextLimit(text, maxChars);
    }

    private String readContinuousTextUntil(
            byte delimiter, int maxChars, boolean matchDecodedLineFeed, int lineContentLimit) {
        StringBuilder text = new StringBuilder(Math.min(maxChars, 128));
        byte[] inputWindow = streamState.inputWindow();
        int bufferedLineCount = 0;
        if (matchDecodedLineFeed) {
            bufferedLineCount = copyBufferedLine(text, maxChars, lineContentLimit);
            if (endsWithLineFeed(text)) {
                decodedLines.consume(bufferedLineCount);
                return text.toString();
            }
        }
        while (true) {
            int available = peekContinuousTextWindow(
                    streamState.inputWindow(), text, maxChars, matchDecodedLineFeed, lineContentLimit);
            if (available < 0) {
                if (bufferedLineCount > 0) {
                    decodedLines.consume(bufferedLineCount);
                }
                return text.toString();
            }
            int decodedLinesCheckpoint = matchDecodedLineFeed ? decodedLines.checkpoint() : -1;
            int processed = 0;
            boolean matched = false;
            RuntimeException runtimeFailure = null;
            Error fatalFailure = null;
            while (processed < available && runtimeFailure == null && fatalFailure == null) {
                try {
                    source.checkDeadline();
                    byte value = inputWindow[processed++];
                    matched = matchDecodedLineFeed
                            ? decodeLineByte(value, text, maxChars, lineContentLimit)
                            : decodeTextByteAndMatch(value, text, maxChars, delimiter);
                    if (matched && matchDecodedLineFeed) {
                        enforceLineContentLimit(text, lineContentLimit);
                    }
                } catch (RuntimeException failure) {
                    runtimeFailure = failure;
                } catch (Error failure) {
                    fatalFailure = failure;
                }
                if (matched) {
                    break;
                }
            }
            if (processed == 0) {
                transaction.discard();
            } else {
                try {
                    output.commit(transaction, processed, budget::addBytes, failures, source::claimTerminal);
                } catch (RuntimeException | Error commitFailure) {
                    if (decodedLinesCheckpoint >= 0) {
                        decodedLines.rollback(decodedLinesCheckpoint);
                    }
                    if (fatalFailure != null && commitFailure != fatalFailure) {
                        throw fatalFailure;
                    }
                    if (runtimeFailure != null && commitFailure != runtimeFailure) {
                        throw runtimeFailure;
                    }
                    throw commitFailure;
                }
            }
            if (fatalFailure != null) {
                throw fatalFailure;
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            if (matched) {
                if (bufferedLineCount > 0) {
                    decodedLines.consume(bufferedLineCount);
                }
                return text.toString();
            }
        }
    }

    private boolean decodeTextByteAndMatch(byte value, StringBuilder text, int maxChars, byte delimiter) {
        decodeTextByte(value, text, maxChars);
        return value == delimiter;
    }

    private boolean decodeLineByte(byte value, StringBuilder text, int maxChars, int lineContentLimit) {
        try {
            decoder.decodeTo(
                    value, (chars, count) -> acceptDecodedLineOutput(chars, count, text), currentLineDecodeLimit());
        } catch (ProtocolTextDecoderState.OutputLimitExceededException exception) {
            chargeContinuousLimitFailure(exception);
        } catch (DecodedLineBuffer.LimitExceededException exception) {
            throw decodedLineBufferTooLarge(exception);
        } catch (CharacterCodingException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", exception);
        }
        enforceTextLimit(text, maxChars);
        boolean matched = endsWithLineFeed(text);
        if (matched) {
            enforceLineContentLimit(text, lineContentLimit);
        }
        return matched;
    }

    private void acceptDecodedLineOutput(char[] chars, int count, StringBuilder text)
            throws DecodedLineBuffer.LimitExceededException {
        // The current line belongs to this response; later lines are charged only when a later reader consumes them.
        int lineFeed = firstLineFeed(chars, count);
        if (lineFeed < 0) {
            budget.addChars(count);
            text.append(chars, 0, count);
            return;
        }
        int currentLineCount = lineFeed + 1;
        budget.addChars(currentLineCount);
        text.append(chars, 0, currentLineCount);
        decodedLines.append(chars, currentLineCount, count - currentLineCount);
    }

    private int copyBufferedLine(StringBuilder text, int maxChars, int lineContentLimit) {
        int count = decodedLines.copyFirstLineTo(text);
        if (count == 0) {
            return 0;
        }
        budget.addChars(count);
        enforceTextLimit(text, maxChars);
        if (endsWithLineFeed(text)) {
            enforceLineContentLimit(text, lineContentLimit);
        }
        return count;
    }

    private int peekContinuousTextWindow(
            byte[] inputWindow, StringBuilder text, int maxChars, boolean matchDecodedLineFeed, int lineContentLimit) {
        if (matchDecodedLineFeed) {
            source.checkLineReadPreconditions(decodedLines.hasPending());
        } else {
            checkNonLineReadPreconditions();
        }
        int remainingBytes = budget.remainingBytes();
        if (remainingBytes == 0) {
            ProtocolOutputEvent terminal = output.peekTerminal(deadlineNanos);
            if (terminal != null) {
                return finishAtTerminal(terminal, text, maxChars, matchDecodedLineFeed, lineContentLimit);
            }
            budget.ensureBytesAvailable(1);
        }
        ProtocolOutputQueue.PeekResult result = output.peekResult(
                inputWindow, 0, Math.min(inputWindow.length, remainingBytes), transaction, deadlineNanos, failures);
        if (result.terminalEvent() != null) {
            return finishAtTerminal(result.terminalEvent(), text, maxChars, matchDecodedLineFeed, lineContentLimit);
        }
        return result.count();
    }

    private int finishAtTerminal(
            ProtocolOutputEvent terminal,
            StringBuilder text,
            int maxChars,
            boolean matchDecodedLineFeed,
            int lineContentLimit) {
        ProtocolOutputEvent claimed = source.claimTerminal(terminal);
        if (claimed.kind() == ProtocolOutputEvent.Kind.EOF) {
            boolean matched = matchDecodedLineFeed
                    ? finishLineDecoder(text, maxChars, lineContentLimit)
                    : finishTextDecoder(text, maxChars);
            if (matched) {
                return -1;
            }
        }
        throw source.refreshClaimedTerminal().terminalFailure(failures);
    }

    private boolean finishTextDecoder(StringBuilder text, int maxChars) {
        int previousLength = text.length();
        try {
            decoder.finish(text, currentTextDecodeLimit(text, maxChars));
        } catch (ProtocolTextDecoderState.OutputLimitExceededException exception) {
            chargeContinuousLimitFailure(exception);
        } catch (CharacterCodingException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", exception);
        }
        int decodedChars = text.length() - previousLength;
        if (decodedChars > 0) {
            budget.addChars(decodedChars);
        }
        enforceTextLimit(text, maxChars);
        return false;
    }

    private boolean finishLineDecoder(StringBuilder text, int maxChars, int lineContentLimit) {
        try {
            decoder.finishTo((chars, count) -> acceptDecodedLineOutput(chars, count, text), currentLineDecodeLimit());
        } catch (ProtocolTextDecoderState.OutputLimitExceededException exception) {
            chargeContinuousLimitFailure(exception);
        } catch (DecodedLineBuffer.LimitExceededException exception) {
            throw decodedLineBufferTooLarge(exception);
        } catch (CharacterCodingException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", exception);
        }
        enforceTextLimit(text, maxChars);
        boolean matched = endsWithLineFeed(text);
        if (matched) {
            enforceLineContentLimit(text, lineContentLimit);
        }
        return matched;
    }

    private int currentTextDecodeLimit(StringBuilder text, int maxChars) {
        int localRemaining = Math.max(0, maxChars - text.length());
        return firstExcessLimit(Math.min(localRemaining, budget.remainingChars()));
    }

    private int currentLineDecodeLimit() {
        return firstExcessLimit(saturatedAdd(budget.remainingChars(), decodedLines.remainingCapacity()));
    }

    private ProtocolSessionException decodedLineBufferTooLarge(DecodedLineBuffer.LimitExceededException exception) {
        return failures.failure(
                ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                "Protocol decoded line buffer exceeds its bounded stream capacity",
                exception);
    }

    private void chargeContinuousLimitFailure(ProtocolTextDecoderState.OutputLimitExceededException exception) {
        budget.addChars(exception.decodedChars());
        throw failures.failure(
                ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, "Protocol response text exceeds maxChars", null);
    }

    private void enforceTextLimit(StringBuilder text, int maxChars) {
        if (text.length() > maxChars) {
            throw failures.failure(
                    ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                    "Protocol response text exceeds maxChars",
                    null);
        }
    }

    private void enforceLineContentLimit(StringBuilder line, int maxChars) {
        int contentLength = line.length() - 1;
        if (contentLength > 0 && line.charAt(contentLength - 1) == '\r') {
            contentLength--;
        }
        if (contentLength > maxChars) {
            throw failures.failure(
                    ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                    "Protocol response line exceeds maxChars",
                    null);
        }
    }

    private ProtocolTextFieldDecoder textFields() {
        if (textFields == null) {
            textFields = new ProtocolTextFieldDecoder(charsetPolicy, budget, failures, source::readAvailableBytes);
        }
        return textFields;
    }

    private void checkNonLineReadPreconditions() {
        source.verifyAccess();
        ensureNonLineReadAllowed();
        source.checkReadPreconditions();
    }

    private static boolean endsWithLineFeed(StringBuilder text) {
        return !text.isEmpty() && text.charAt(text.length() - 1) == '\n';
    }

    private static int firstLineFeed(char[] chars, int count) {
        for (int index = 0; index < count; index++) {
            if (chars[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    static int pendingByteLimit(ProtocolSessionSettings options) {
        return IncrementalTextDecoder.pendingByteLimitFor(options.maxResponseBytes());
    }

    static int outputWithoutInputLimit(ProtocolSessionSettings options) {
        return IncrementalTextDecoder.outputWithoutInputLimitFor(firstExcessLimit(options.maxResponseChars()));
    }

    static int decodedLineBufferLimit(ProtocolSessionSettings options) {
        return options.maxResponseChars();
    }

    private static int saturatedAdd(int left, int right) {
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static int firstExcessLimit(int remainingChars) {
        return remainingChars == Integer.MAX_VALUE ? Integer.MAX_VALUE : remainingChars + 1;
    }

    static final class StreamState {

        private final ProtocolTextDecoderState decoder;
        private final DecodedLineBuffer decodedLines;
        private final int inputWindowSize;
        private byte[] inputWindow;

        StreamState(ProtocolSessionSettings options, ProtocolTextDecoderState decoder) {
            ProtocolSessionSettings configuredOptions = Objects.requireNonNull(options, "options");
            this.decoder = Objects.requireNonNull(decoder, "decoder");
            decodedLines = new DecodedLineBuffer(decodedLineBufferLimit(configuredOptions));
            inputWindowSize = Math.min(INPUT_WINDOW_SIZE, pendingByteLimit(configuredOptions));
        }

        boolean hasPendingDecodedLines() {
            return decodedLines.hasPending();
        }

        byte[] inputWindow() {
            if (inputWindow == null) {
                inputWindow = new byte[inputWindowSize];
            }
            return inputWindow;
        }

        boolean inputWindowAllocated() {
            return inputWindow != null;
        }
    }
}
