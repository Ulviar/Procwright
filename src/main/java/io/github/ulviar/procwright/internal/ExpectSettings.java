/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.session.ExpectTranscriptValues;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable expect helper configuration. */
public record ExpectSettings(
        Duration timeout,
        int transcriptLimit,
        int matchBufferLimit,
        boolean ansiControlSequenceStripping,
        ExpectTranscriptValues transcriptValues,
        Optional<Charset> outputCharset) {

    public ExpectSettings {
        timeout = DurationSupport.requirePositive(timeout, "timeout");
        transcriptLimit = positive(transcriptLimit, "transcriptLimit");
        matchBufferLimit = positive(matchBufferLimit, "matchBufferLimit");
        Objects.requireNonNull(transcriptValues, "transcriptValues");
        Objects.requireNonNull(outputCharset, "outputCharset");
    }

    public static ExpectSettings defaults() {
        return new ExpectSettings(
                Duration.ofSeconds(5), 64 * 1024, 64 * 1024, false, ExpectTranscriptValues.REDACTED, Optional.empty());
    }

    public ExpectSettings withTimeout(Duration value) {
        return new ExpectSettings(
                value,
                transcriptLimit,
                matchBufferLimit,
                ansiControlSequenceStripping,
                transcriptValues,
                outputCharset);
    }

    public ExpectSettings withTranscriptLimit(int value) {
        return new ExpectSettings(
                timeout, value, matchBufferLimit, ansiControlSequenceStripping, transcriptValues, outputCharset);
    }

    public ExpectSettings withMatchBufferLimit(int value) {
        return new ExpectSettings(
                timeout, transcriptLimit, value, ansiControlSequenceStripping, transcriptValues, outputCharset);
    }

    public ExpectSettings withAnsiControlSequenceStripping() {
        return new ExpectSettings(timeout, transcriptLimit, matchBufferLimit, true, transcriptValues, outputCharset);
    }

    public ExpectSettings withTranscriptValues(ExpectTranscriptValues value) {
        return new ExpectSettings(
                timeout, transcriptLimit, matchBufferLimit, ansiControlSequenceStripping, value, outputCharset);
    }

    public ExpectSettings withOutputCharset(Charset value) {
        return new ExpectSettings(
                timeout,
                transcriptLimit,
                matchBufferLimit,
                ansiControlSequenceStripping,
                transcriptValues,
                Optional.of(Objects.requireNonNull(value, "outputCharset")));
    }

    public Charset outputCharsetOr(Charset fallback) {
        return outputCharset.orElse(Objects.requireNonNull(fallback, "fallback"));
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
