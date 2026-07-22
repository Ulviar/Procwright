/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

final class ContentLengthHeaderTestCases {

    private ContentLengthHeaderTestCases() {}

    static Stream<HeaderCase> cases() {
        return Stream.of(
                valid("canonical", ascii("Content-Length: 2\r\n\r\n")),
                valid("mixed case and OWS", ascii("cOnTeNt-LeNgTh:\t 2 \t\r\nX-Meta:\t visible !~ \t\r\n\r\n")),
                invalid(
                        "missing length",
                        ascii("Content-Type: application/json\r\n\r\n"),
                        IntegrationProtocolException.Reason.MISSING_LENGTH),
                invalid(
                        "missing colon",
                        ascii("Content-Length 2\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_HEADER),
                invalid(
                        "whitespace before colon",
                        ascii("Content-Length \t: 2\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_HEADER),
                invalid(
                        "signed length",
                        ascii("Content-Length: +2\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_LENGTH),
                invalid(
                        "empty length",
                        ascii("Content-Length: \t \r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_LENGTH),
                invalid(
                        "negative length",
                        ascii("Content-Length: -1\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_LENGTH),
                invalid(
                        "overflowing length",
                        ascii("Content-Length: 2147483648\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_LENGTH),
                invalid(
                        "case-insensitive duplicate",
                        ascii("Content-Length: 2\r\ncontent-length:\t2\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_HEADER),
                invalid(
                        "control in value",
                        bytes("Content-Length: 2\r\nX-Meta: before", 0x01, "after\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_HEADER),
                invalid(
                        "non-ASCII in value",
                        bytes("Content-Length: 2\r\nX-Meta: before", 0x80, "after\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_HEADER),
                invalid(
                        "obsolete folded value",
                        ascii("Content-Length: 2\r\nX-Meta: first\r\n second\r\n\r\n"),
                        IntegrationProtocolException.Reason.BAD_HEADER),
                invalid("EOF before header", new byte[0], IntegrationProtocolException.Reason.EOF),
                invalid(
                        "EOF inside terminator",
                        ascii("Content-Length: 2\r\n\r"),
                        IntegrationProtocolException.Reason.EOF));
    }

    private static HeaderCase valid(String name, byte[] header) {
        return new HeaderCase(name, header, null);
    }

    private static HeaderCase invalid(String name, byte[] header, IntegrationProtocolException.Reason expectedReason) {
        return new HeaderCase(name, header, expectedReason);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(String prefix, int value, String suffix) {
        byte[] first = ascii(prefix);
        byte[] last = ascii(suffix);
        byte[] result = new byte[first.length + 1 + last.length];
        System.arraycopy(first, 0, result, 0, first.length);
        result[first.length] = (byte) value;
        System.arraycopy(last, 0, result, first.length + 1, last.length);
        return result;
    }

    record HeaderCase(String name, byte[] header, IntegrationProtocolException.Reason expectedReason) {

        HeaderCase {
            header = header.clone();
        }

        @Override
        public byte[] header() {
            return header.clone();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
