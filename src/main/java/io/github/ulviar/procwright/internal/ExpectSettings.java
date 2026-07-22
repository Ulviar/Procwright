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
        Optional<Charset> charset,
        boolean ansiControlSequenceStripping,
        ExpectTranscriptValues transcriptValues) {

    public ExpectSettings {
        timeout = DurationSupport.requirePositive(timeout, "timeout");
        transcriptLimit = positive(transcriptLimit, "transcriptLimit");
        matchBufferLimit = positive(matchBufferLimit, "matchBufferLimit");
        charset = Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(transcriptValues, "transcriptValues");
    }

    public static ExpectSettings defaults() {
        return new ExpectSettings(
                Duration.ofSeconds(5), 64 * 1024, 64 * 1024, Optional.empty(), false, ExpectTranscriptValues.REDACTED);
    }

    public Charset charsetFor(Charset sessionCharset) {
        return charset.orElse(Objects.requireNonNull(sessionCharset, "sessionCharset"));
    }

    public ExpectSettings withTimeout(Duration value) {
        return new ExpectSettings(
                value, transcriptLimit, matchBufferLimit, charset, ansiControlSequenceStripping, transcriptValues);
    }

    public ExpectSettings withTranscriptLimit(int value) {
        return new ExpectSettings(
                timeout, value, matchBufferLimit, charset, ansiControlSequenceStripping, transcriptValues);
    }

    public ExpectSettings withMatchBufferLimit(int value) {
        return new ExpectSettings(
                timeout, transcriptLimit, value, charset, ansiControlSequenceStripping, transcriptValues);
    }

    public ExpectSettings withCharset(Charset value) {
        return new ExpectSettings(
                timeout,
                transcriptLimit,
                matchBufferLimit,
                Optional.of(Objects.requireNonNull(value, "charset")),
                ansiControlSequenceStripping,
                transcriptValues);
    }

    public ExpectSettings withAnsiControlSequenceStripping() {
        return new ExpectSettings(timeout, transcriptLimit, matchBufferLimit, charset, true, transcriptValues);
    }

    public ExpectSettings withTranscriptValues(ExpectTranscriptValues value) {
        return new ExpectSettings(
                timeout, transcriptLimit, matchBufferLimit, charset, ansiControlSequenceStripping, value);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
