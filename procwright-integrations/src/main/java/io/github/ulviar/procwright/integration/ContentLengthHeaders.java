/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.BAD_HEADER;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.BAD_LENGTH;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.EOF;
import static io.github.ulviar.procwright.integration.IntegrationProtocolException.Reason.MISSING_LENGTH;

import java.io.IOException;
import java.util.function.IntSupplier;

final class ContentLengthHeaders {

    private static final int MAX_HEADER_BYTES = 8192;

    private ContentLengthHeaders() {}

    static int read(ByteSource source) throws IOException {
        byte[] header = new byte[MAX_HEADER_BYTES];
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        for (int index = 0; index < header.length; index++) {
            int value = source.read();
            if (value < 0) {
                throw new IntegrationProtocolException(EOF, "Input ended before frame headers were complete");
            }
            header[index] = (byte) value;
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && value == '\n') {
                return parse(header, index + 1);
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = value;
        }
        throw new IntegrationProtocolException(BAD_HEADER, "Frame headers exceed limit");
    }

    static int readProtocol(IntSupplier source) {
        try {
            return read(source::getAsInt);
        } catch (IOException impossible) {
            throw new AssertionError("Protocol byte source does not throw checked I/O failures", impossible);
        }
    }

    private static int parse(byte[] header, int length) {
        validateWireBytes(header, length);
        Integer contentLength = null;
        int lineStart = 0;
        while (lineStart < length) {
            int lineEnd = findLineEnd(header, lineStart, length);
            if (lineEnd == lineStart) {
                if (lineEnd + 2 != length) {
                    throw new IntegrationProtocolException(BAD_HEADER, "Unexpected empty frame header");
                }
                break;
            }
            int separator = findSeparator(header, lineStart, lineEnd);
            if (separator < 0) {
                throw new IntegrationProtocolException(BAD_HEADER, "Malformed frame header");
            }
            if (isContentLength(header, lineStart, separator)) {
                if (contentLength != null) {
                    throw new IntegrationProtocolException(BAD_HEADER, "Duplicate Content-Length header");
                }
                contentLength = parseLength(header, separator + 1, lineEnd);
            }
            lineStart = lineEnd + 2;
        }
        if (contentLength == null) {
            throw new IntegrationProtocolException(MISSING_LENGTH, "Content-Length header is required");
        }
        return contentLength;
    }

    private static void validateWireBytes(byte[] header, int length) {
        for (int index = 0; index < length; index++) {
            int value = header[index] & 0xFF;
            if (value == '\r') {
                if (index + 1 >= length || header[index + 1] != '\n') {
                    throw new IntegrationProtocolException(BAD_HEADER, "Frame headers must use CRLF line endings");
                }
                index++;
            } else if (value != '\t' && (value < 0x20 || value > 0x7E)) {
                throw new IntegrationProtocolException(BAD_HEADER, "Frame headers must contain ASCII text");
            }
        }
    }

    private static int findLineEnd(byte[] header, int lineStart, int length) {
        for (int index = lineStart; index + 1 < length; index++) {
            if (header[index] == '\r' && header[index + 1] == '\n') {
                return index;
            }
        }
        throw new IntegrationProtocolException(BAD_HEADER, "Frame header line is not terminated");
    }

    private static int findSeparator(byte[] header, int lineStart, int lineEnd) {
        for (int index = lineStart; index < lineEnd; index++) {
            int value = header[index] & 0xFF;
            if (value == ':') {
                return index == lineStart ? -1 : index;
            }
            if (!isToken(value)) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isToken(int value) {
        return value >= '0' && value <= '9'
                || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || switch (value) {
                    case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~' -> true;
                    default -> false;
                };
    }

    private static boolean isContentLength(byte[] header, int start, int end) {
        byte[] expected = {'c', 'o', 'n', 't', 'e', 'n', 't', '-', 'l', 'e', 'n', 'g', 't', 'h'};
        if (end - start != expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            int actual = header[start + index] & 0xFF;
            if (actual >= 'A' && actual <= 'Z') {
                actual += 'a' - 'A';
            }
            if (actual != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static int parseLength(byte[] header, int start, int end) {
        while (start < end && isOptionalWhitespace(header[start] & 0xFF)) {
            start++;
        }
        while (end > start && isOptionalWhitespace(header[end - 1] & 0xFF)) {
            end--;
        }
        if (start == end) {
            throw new IntegrationProtocolException(BAD_LENGTH, "Content-Length must be a decimal integer");
        }
        int length = 0;
        for (int index = start; index < end; index++) {
            int value = header[index] & 0xFF;
            if (value < '0' || value > '9') {
                throw new IntegrationProtocolException(BAD_LENGTH, "Content-Length must be a decimal integer");
            }
            int digit = value - '0';
            if (length > (Integer.MAX_VALUE - digit) / 10) {
                throw new IntegrationProtocolException(BAD_LENGTH, "Content-Length exceeds the supported range");
            }
            length = length * 10 + digit;
        }
        return length;
    }

    private static boolean isOptionalWhitespace(int value) {
        return value == ' ' || value == '\t';
    }

    @FunctionalInterface
    interface ByteSource {
        int read() throws IOException;
    }
}
