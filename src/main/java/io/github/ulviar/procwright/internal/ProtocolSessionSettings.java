/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Immutable typed protocol budgets and text policy. */
public record ProtocolSessionSettings(
        Duration requestTimeout,
        int transcriptLimit,
        int outputBacklogLimit,
        int maxRequestBytes,
        int maxRequestChars,
        int maxResponseBytes,
        int maxResponseChars,
        CharsetPolicy charsetPolicy) {

    public ProtocolSessionSettings {
        requestTimeout = DurationSupport.requirePositive(requestTimeout, "requestTimeout");
        transcriptLimit = positive(transcriptLimit, "transcriptLimit");
        outputBacklogLimit = positive(outputBacklogLimit, "outputBacklogLimit");
        maxRequestBytes = positive(maxRequestBytes, "maxRequestBytes");
        maxRequestChars = positive(maxRequestChars, "maxRequestChars");
        maxResponseBytes = positive(maxResponseBytes, "maxResponseBytes");
        maxResponseChars = positive(maxResponseChars, "maxResponseChars");
        Objects.requireNonNull(charsetPolicy, "charsetPolicy");
    }

    public static ProtocolSessionSettings defaults() {
        return new ProtocolSessionSettings(
                Duration.ofSeconds(5),
                64 * 1024,
                1024 * 1024,
                1024 * 1024,
                Integer.MAX_VALUE,
                1024 * 1024,
                Integer.MAX_VALUE,
                CharsetPolicy.replace(StandardCharsets.UTF_8));
    }

    public ProtocolSessionSettings withRequestTimeout(Duration value) {
        return copy(
                value,
                transcriptLimit,
                outputBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    public ProtocolSessionSettings withTranscriptLimit(int value) {
        return copy(
                requestTimeout,
                value,
                outputBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    public ProtocolSessionSettings withOutputBacklogLimit(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                value,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    public ProtocolSessionSettings withMaxRequestBytes(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                outputBacklogLimit,
                value,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    public ProtocolSessionSettings withMaxRequestChars(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                outputBacklogLimit,
                maxRequestBytes,
                value,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    public ProtocolSessionSettings withMaxResponseBytes(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                outputBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                value,
                maxResponseChars,
                charsetPolicy);
    }

    public ProtocolSessionSettings withMaxResponseChars(int value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                outputBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                value,
                charsetPolicy);
    }

    public ProtocolSessionSettings withCharsetPolicy(CharsetPolicy value) {
        return copy(
                requestTimeout,
                transcriptLimit,
                outputBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                value);
    }

    private static ProtocolSessionSettings copy(
            Duration requestTimeout,
            int transcriptLimit,
            int outputBacklogLimit,
            int maxRequestBytes,
            int maxRequestChars,
            int maxResponseBytes,
            int maxResponseChars,
            CharsetPolicy charsetPolicy) {
        return new ProtocolSessionSettings(
                requestTimeout,
                transcriptLimit,
                outputBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
