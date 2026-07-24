/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.util.Objects;

/**
 * Request-scoped response reader over stream-owned decoder state with deadline and response-budget accounting.
 */
final class ProtocolResponseReader implements ProtocolReader {

    private final ProtocolOutputQueue output;
    private final long deadlineNanos;
    private final ProtocolResponseBudget budget;
    private final ProtocolRuntimeFailures failures;
    private final ProtocolTextDecoderState textDecoder;
    private final CharsetPolicy charsetPolicy;
    private final RequestCapabilityScope capabilityScope;
    private final ProtocolOutputQueue.ReadWindow continuousTextTransaction = new ProtocolOutputQueue.ReadWindow();
    private ProtocolTextFieldDecoder textFields;
    private boolean readStarted;
    private ProtocolOutputEvent claimedTerminal;

    ProtocolResponseReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            long deadlineNanos,
            ProtocolResponseBudget budget,
            ProtocolTextDecoderState textDecoder,
            ProtocolRuntimeFailures failures,
            RequestCapabilityScope capabilityScope) {
        this.output = Objects.requireNonNull(output, "output");
        ProtocolSessionSettings configuredOptions = Objects.requireNonNull(options, "options");
        this.deadlineNanos = deadlineNanos;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.textDecoder = Objects.requireNonNull(textDecoder, "textDecoder");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.capabilityScope = Objects.requireNonNull(capabilityScope, "capabilityScope");
        charsetPolicy = configuredOptions.charsetPolicy();
    }

    @Override
    public byte readByte() {
        checkReadPreconditions();
        ensureContinuousDecoderBoundary();
        return (byte) readOneUnsignedByte();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            capabilityScope.verifyAccess();
            return 0;
        }
        checkReadPreconditions();
        ensureContinuousDecoderBoundary();
        return readAvailableBytes(buffer, offset, length);
    }

    @Override
    public byte[] readExactly(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        if (length == 0) {
            capabilityScope.verifyAccess();
            return new byte[0];
        }
        checkReadPreconditions();
        ensureContinuousDecoderBoundary();
        budget.ensureBytesAvailable(length);
        byte[] result = new byte[length];
        int read = 0;
        while (read < length) {
            read += readAvailableBytes(result, read, length - read);
        }
        return result;
    }

    @Override
    public String readTextExactly(int byteLength, int maxChars) {
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must not be negative");
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (byteLength == 0) {
            capabilityScope.verifyAccess();
            return "";
        }
        checkReadPreconditions();
        ensureContinuousDecoderBoundary();
        budget.ensureCharacterBudgetOpen();
        budget.ensureBytesAvailable(byteLength);
        return textFields().decode(byteLength, maxChars);
    }

    @Override
    public byte[] readUntil(byte delimiter, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        checkReadPreconditions();
        ensureContinuousDecoderBoundary();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        while (bytes.size() < maxBytes) {
            byte value = (byte) readOneUnsignedByte();
            bytes.write(value);
            if (value == delimiter) {
                return bytes.toByteArray();
            }
        }
        throw failures.failure(
                ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                "Protocol response exceeds delimiter read limit",
                null);
    }

    @Override
    public String readLine(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        checkLineReadPreconditions();
        budget.ensureCharacterBudgetOpen();
        String line = readDecodedLine(maxChars);
        if (line.endsWith("\n")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line;
    }

    private void checkLineReadPreconditions() {
        capabilityScope.verifyAccess();
        if (!textDecoder.hasPendingLineOutput()) {
            ProtocolOutputEvent terminal = claimInitialTerminal();
            if (terminal != null) {
                throw terminal.terminalFailure(failures);
            }
        }
        checkDeadline();
    }

    @Override
    public String readTextUntil(byte delimiter, int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        checkReadPreconditions();
        budget.ensureCharacterBudgetOpen();
        return readContinuousTextUntil(delimiter, maxChars, false, -1);
    }

    private int readOneUnsignedByte() {
        checkReadPreconditions();
        budget.ensureBytesAvailable(1);
        return output.readUnsignedByte(deadlineNanos, failures, budget::addBytes, this::claimTerminal);
    }

    private int readAvailableBytes(byte[] buffer, int offset, int length) {
        checkReadPreconditions();
        budget.ensureBytesAvailable(1);
        return output.read(buffer, offset, length, deadlineNanos, failures, budget::addBytes, this::claimTerminal);
    }

    private void ensureContinuousDecoderBoundary() {
        if (textDecoder.hasPendingInput() || textDecoder.hasPendingLineOutput()) {
            throw failures.failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED,
                    "Cannot start a raw or complete-field read while continuous text decoding has pending input or output",
                    null);
        }
    }

    private void checkReadPreconditions() {
        capabilityScope.verifyAccess();
        if (textDecoder.hasPendingLineOutput()) {
            throw failures.failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED,
                    "Cannot use a non-line reader while decoded line output remains pending",
                    null);
        }
        ProtocolOutputEvent terminal = claimInitialTerminal();
        if (terminal != null) {
            throw terminal.terminalFailure(failures);
        }
        checkDeadline();
    }

    private synchronized ProtocolOutputEvent claimInitialTerminal() {
        if (claimedTerminal != null) {
            return refreshClaimedTerminal();
        }
        if (readStarted) {
            return null;
        }
        // A terminal already pending at the stream's first read predates this read's generic
        // deadline/budget checks. Once an ordinary read starts, those checks keep their priority.
        readStarted = true;
        return claimTerminal(output.peekTerminal(deadlineNanos));
    }

    private synchronized ProtocolOutputEvent claimTerminal(ProtocolOutputEvent terminal) {
        if (claimedTerminal == null && terminal != null) {
            claimedTerminal = output.refreshTerminal(terminal, deadlineNanos);
        }
        return claimedTerminal;
    }

    private synchronized ProtocolOutputEvent refreshClaimedTerminal() {
        claimedTerminal =
                output.refreshTerminal(Objects.requireNonNull(claimedTerminal, "claimedTerminal"), deadlineNanos);
        return claimedTerminal;
    }

    private String readDecodedLine(int maxChars) {
        int readLimit = maxChars >= Integer.MAX_VALUE - 2 ? Integer.MAX_VALUE : maxChars + 2;
        return readContinuousTextUntil((byte) '\n', readLimit, true, maxChars);
    }

    private void checkDeadline() {
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw failures.timeout(null);
        }
    }

    private void decodeTextByte(byte value, StringBuilder text, int maxChars) {
        int previousLength = text.length();
        try {
            textDecoder.decode(value, text, currentTextDecodeLimit(text, maxChars));
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
        int carriedLineOutput = 0;
        if (matchDecodedLineFeed) {
            carriedLineOutput = copyDecodedLineSuffix(text, maxChars, lineContentLimit);
            if (endsWithLineFeed(text)) {
                textDecoder.consumeLineOutput(carriedLineOutput);
                return text.toString();
            }
        }
        byte[] inputWindow = textDecoder.inputWindow();
        while (true) {
            int available =
                    peekContinuousTextWindow(inputWindow, text, maxChars, matchDecodedLineFeed, lineContentLimit);
            if (available < 0) {
                if (carriedLineOutput > 0) {
                    textDecoder.consumeLineOutput(carriedLineOutput);
                }
                return text.toString();
            }
            int lineOutputCheckpoint = matchDecodedLineFeed ? textDecoder.lineOutputCheckpoint() : -1;
            int processed = 0;
            boolean matched = false;
            RuntimeException runtimeFailure = null;
            Error fatalFailure = null;
            while (processed < available && runtimeFailure == null && fatalFailure == null) {
                try {
                    checkDeadline();
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
                continuousTextTransaction.discard();
            } else {
                try {
                    output.commit(
                            continuousTextTransaction, processed, budget::addBytes, failures, this::claimTerminal);
                } catch (RuntimeException | Error commitFailure) {
                    if (lineOutputCheckpoint >= 0) {
                        textDecoder.rollbackLineOutput(lineOutputCheckpoint);
                    }
                    if (fatalFailure != null && commitFailure != fatalFailure) {
                        SuppressionSupport.attach(fatalFailure, commitFailure);
                        throw fatalFailure;
                    }
                    if (runtimeFailure != null && commitFailure != runtimeFailure) {
                        SuppressionSupport.attach(runtimeFailure, commitFailure);
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
                if (carriedLineOutput > 0) {
                    textDecoder.consumeLineOutput(carriedLineOutput);
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
            textDecoder.decodeTo(
                    value, (chars, count) -> acceptDecodedLineOutput(chars, count, text), currentLineDecodeLimit());
        } catch (ProtocolTextDecoderState.OutputLimitExceededException exception) {
            chargeContinuousLimitFailure(exception);
        } catch (ProtocolTextDecoderState.DecodedLineSuffixLimitExceededException exception) {
            throw decodedLineSuffixTooLarge(exception);
        } catch (CharacterCodingException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", exception);
        }
        enforceTextLimit(text, maxChars);
        boolean matched = !text.isEmpty() && text.charAt(text.length() - 1) == '\n';
        if (matched) {
            enforceLineContentLimit(text, lineContentLimit);
        }
        return matched;
    }

    private void acceptDecodedLineOutput(char[] chars, int count, StringBuilder text)
            throws ProtocolTextDecoderState.DecodedLineSuffixLimitExceededException {
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
        textDecoder.appendLineOutput(chars, currentLineCount, count - currentLineCount);
    }

    private int copyDecodedLineSuffix(StringBuilder text, int maxChars, int lineContentLimit) {
        int count = textDecoder.copyFirstLineOutput(text);
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

    private int peekContinuousTextWindow(
            byte[] inputWindow, StringBuilder text, int maxChars, boolean matchDecodedLineFeed, int lineContentLimit) {
        if (matchDecodedLineFeed) {
            checkLineReadPreconditions();
        } else {
            checkReadPreconditions();
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
                inputWindow,
                0,
                Math.min(inputWindow.length, remainingBytes),
                continuousTextTransaction,
                deadlineNanos,
                failures);
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
        ProtocolOutputEvent claimed = claimTerminal(terminal);
        if (claimed.kind() == ProtocolOutputEvent.Kind.EOF) {
            boolean matched = matchDecodedLineFeed
                    ? finishLineDecoder(text, maxChars, lineContentLimit)
                    : finishTextDecoder(text, maxChars);
            if (matched) {
                return -1;
            }
        }
        throw refreshClaimedTerminal().terminalFailure(failures);
    }

    private boolean finishTextDecoder(StringBuilder text, int maxChars) {
        int previousLength = text.length();
        try {
            textDecoder.finish(text, currentTextDecodeLimit(text, maxChars));
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
            textDecoder.finishTo(
                    (chars, count) -> acceptDecodedLineOutput(chars, count, text), currentLineDecodeLimit());
        } catch (ProtocolTextDecoderState.OutputLimitExceededException exception) {
            chargeContinuousLimitFailure(exception);
        } catch (ProtocolTextDecoderState.DecodedLineSuffixLimitExceededException exception) {
            throw decodedLineSuffixTooLarge(exception);
        } catch (CharacterCodingException exception) {
            throw failures.failure(
                    ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol response", exception);
        }
        enforceTextLimit(text, maxChars);
        boolean matched = !text.isEmpty() && text.charAt(text.length() - 1) == '\n';
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
        return firstExcessLimit(saturatedAdd(budget.remainingChars(), textDecoder.remainingLineOutputCapacity()));
    }

    private ProtocolSessionException decodedLineSuffixTooLarge(
            ProtocolTextDecoderState.DecodedLineSuffixLimitExceededException exception) {
        return failures.failure(
                ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                "Protocol decoded line suffix exceeds its bounded stream capacity",
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

    private ProtocolTextFieldDecoder textFields() {
        if (textFields == null) {
            textFields = new ProtocolTextFieldDecoder(charsetPolicy, budget, failures, this::readAvailableBytes);
        }
        return textFields;
    }

    static int pendingByteLimit(ProtocolSessionSettings options) {
        int configuredLimit = Math.min(options.maxResponseBytes(), options.outputBacklogLimit());
        return IncrementalTextDecoder.pendingByteLimitFor(configuredLimit);
    }

    static int outputWithoutInputLimit(ProtocolSessionSettings options) {
        return IncrementalTextDecoder.outputWithoutInputLimitFor(firstExcessLimit(options.maxResponseChars()));
    }

    static int decodedLineSuffixLimit(ProtocolSessionSettings options) {
        return Math.min(options.outputBacklogLimit(), options.maxResponseChars());
    }

    private static int saturatedAdd(int left, int right) {
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static int firstExcessLimit(int remainingChars) {
        return remainingChars == Integer.MAX_VALUE ? Integer.MAX_VALUE : remainingChars + 1;
    }
}
