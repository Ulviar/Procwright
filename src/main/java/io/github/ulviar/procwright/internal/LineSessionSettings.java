/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Immutable line protocol limits and decoding behavior. */
public record LineSessionSettings(
        Duration requestTimeout,
        int transcriptLimit,
        int maxRequestBytes,
        int maxRequestChars,
        int maxResponseLines,
        int maxResponseChars,
        CharsetPolicy charsetPolicy,
        ResponseDecoder responseDecoder) {

    private static final int ONE_MIB = 1024 * 1024;

    public LineSessionSettings {
        requestTimeout = DurationSupport.requirePositive(requestTimeout, "requestTimeout");
        transcriptLimit = positive(transcriptLimit, "transcriptLimit");
        maxRequestBytes = positive(maxRequestBytes, "maxRequestBytes");
        maxRequestChars = positive(maxRequestChars, "maxRequestChars");
        maxResponseLines = positive(maxResponseLines, "maxResponseLines");
        maxResponseChars = positive(maxResponseChars, "maxResponseChars");
        Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        Objects.requireNonNull(responseDecoder, "responseDecoder");
    }

    public static LineSessionSettings defaults() {
        return new LineSessionSettings(
                Duration.ofSeconds(5),
                64 * 1024,
                ONE_MIB,
                ONE_MIB,
                1024,
                ONE_MIB,
                CharsetPolicy.replace(StandardCharsets.UTF_8),
                ResponseDecoder.firstLine());
    }

    public Charset charset() {
        return charsetPolicy.charset();
    }

    public LineSessionSettings withRequestTimeout(Duration value) {
        return copy(
                value,
                transcriptLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseLines,
                maxResponseChars,
                charsetPolicy,
                responseDecoder);
    }

    public LineSessionSettings withTranscriptLimit(int value) {
        return copy(
                requestTimeout,
                value,
                maxRequestBytes,
                maxRequestChars,
                maxResponseLines,
                maxResponseChars,
                charsetPolicy,
                responseDecoder);
    }

    public LineSessionSettings withMaxRequestBytes(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                value,
                maxRequestChars,
                maxResponseLines,
                maxResponseChars,
                charsetPolicy,
                responseDecoder);
    }

    public LineSessionSettings withMaxRequestChars(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                maxRequestBytes,
                value,
                maxResponseLines,
                maxResponseChars,
                charsetPolicy,
                responseDecoder);
    }

    public LineSessionSettings withMaxResponseLines(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                maxRequestBytes,
                maxRequestChars,
                value,
                maxResponseChars,
                charsetPolicy,
                responseDecoder);
    }

    public LineSessionSettings withMaxResponseChars(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseLines,
                value,
                charsetPolicy,
                responseDecoder);
    }

    public LineSessionSettings withCharsetPolicy(CharsetPolicy value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseLines,
                maxResponseChars,
                value,
                responseDecoder);
    }

    public LineSessionSettings withResponseDecoder(ResponseDecoder value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseLines,
                maxResponseChars,
                charsetPolicy,
                value);
    }

    private static LineSessionSettings copy(
            Duration requestTimeout,
            int transcriptLimit,
            int maxRequestBytes,
            int maxRequestChars,
            int maxResponseLines,
            int maxResponseChars,
            CharsetPolicy charsetPolicy,
            ResponseDecoder responseDecoder) {
        return new LineSessionSettings(
                requestTimeout,
                transcriptLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseLines,
                maxResponseChars,
                charsetPolicy,
                responseDecoder);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
