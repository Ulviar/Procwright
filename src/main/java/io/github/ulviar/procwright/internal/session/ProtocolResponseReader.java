/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.io.ByteArrayOutputStream;
import java.util.Objects;

/** Request-scoped protocol-reader facade over raw and text operations. */
final class ProtocolResponseReader implements ProtocolReader {

    private final ProtocolResponseBudget budget;
    private final ProtocolRuntimeFailures failures;
    private final ProtocolReadSource source;
    private final ProtocolTextReader textReader;

    ProtocolResponseReader(
            ProtocolOutputQueue output,
            ProtocolSessionSettings options,
            long deadlineNanos,
            ProtocolResponseBudget budget,
            ProtocolTextReader.StreamState textStream,
            ProtocolRuntimeFailures failures,
            RequestCapabilityScope capabilityScope) {
        ProtocolOutputQueue configuredOutput = Objects.requireNonNull(output, "output");
        ProtocolSessionSettings configuredOptions = Objects.requireNonNull(options, "options");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.failures = Objects.requireNonNull(failures, "failures");
        source = new ProtocolReadSource(
                configuredOutput,
                deadlineNanos,
                budget,
                failures,
                Objects.requireNonNull(capabilityScope, "capabilityScope"));
        textReader = new ProtocolTextReader(
                configuredOutput,
                configuredOptions,
                deadlineNanos,
                budget,
                Objects.requireNonNull(textStream, "textStream"),
                failures,
                source);
    }

    @Override
    public byte readByte() {
        checkRawReadPreconditions();
        return (byte) source.readOneUnsignedByte();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            source.verifyAccess();
            return 0;
        }
        checkRawReadPreconditions();
        return source.readAvailableBytes(buffer, offset, length);
    }

    @Override
    public byte[] readExactly(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        if (length == 0) {
            source.verifyAccess();
            return new byte[0];
        }
        checkRawReadPreconditions();
        budget.ensureBytesAvailable(length);
        byte[] result = new byte[length];
        int read = 0;
        while (read < length) {
            read += source.readAvailableBytes(result, read, length - read);
        }
        return result;
    }

    @Override
    public String readTextExactly(int byteLength, int maxChars) {
        return textReader.readTextExactly(byteLength, maxChars);
    }

    @Override
    public byte[] readUntil(byte delimiter, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        checkRawReadPreconditions();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        while (bytes.size() < maxBytes) {
            byte value = (byte) source.readOneUnsignedByte();
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
        return textReader.readLine(maxChars);
    }

    @Override
    public String readTextUntil(byte delimiter, int maxChars) {
        return textReader.readTextUntil(delimiter, maxChars);
    }

    private void checkRawReadPreconditions() {
        source.verifyAccess();
        textReader.ensureNonLineReadAllowed();
        source.checkReadPreconditions();
        textReader.ensureRawReadAllowed();
    }
}
