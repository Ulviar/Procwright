/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Default policies for expect-style prompt automation.
 *
 * <p>Charset precedence: default options follow the charset of the session the helper runs on, so expect reads and
 * session writes stay consistent. A charset configured explicitly through a constructor or
 * {@link #withCharset(Charset)} takes precedence over the session charset.
 */
public final class ExpectOptions {

    private static final ExpectOptions DEFAULTS = new ExpectOptions(
            Duration.ofSeconds(5),
            64 * 1024,
            64 * 1024,
            StandardCharsets.UTF_8,
            false,
            ExpectOutputFilter.identity(),
            ExpectTranscriptValues.REDACTED);

    private final Duration timeout;
    private final int transcriptLimit;
    private final int matchBufferLimit;
    private final Charset charset;
    private final boolean charsetExplicit;
    private final ExpectOutputFilter outputFilter;
    private final ExpectTranscriptValues transcriptValues;

    /**
     * Creates expect options from explicit policies.
     *
     * @param timeout default match timeout
     * @param transcriptLimit maximum retained transcript characters
     * @param matchBufferLimit maximum retained stdout match characters
     * @param charset output charset
     * @param outputFilter output filter applied before matching and transcript capture
     */
    public ExpectOptions(
            Duration timeout,
            int transcriptLimit,
            int matchBufferLimit,
            Charset charset,
            ExpectOutputFilter outputFilter) {
        this(timeout, transcriptLimit, matchBufferLimit, charset, outputFilter, ExpectTranscriptValues.REDACTED);
    }

    /**
     * Creates expect options from explicit policies.
     *
     * @param timeout default match timeout
     * @param transcriptLimit maximum retained transcript characters
     * @param matchBufferLimit maximum retained stdout match characters
     * @param charset output charset
     * @param outputFilter output filter applied before matching and transcript capture
     * @param transcriptValues whether action transcript entries include caller-provided values
     */
    public ExpectOptions(
            Duration timeout,
            int transcriptLimit,
            int matchBufferLimit,
            Charset charset,
            ExpectOutputFilter outputFilter,
            ExpectTranscriptValues transcriptValues) {
        this(timeout, transcriptLimit, matchBufferLimit, charset, true, outputFilter, transcriptValues);
    }

    private ExpectOptions(
            Duration timeout,
            int transcriptLimit,
            int matchBufferLimit,
            Charset charset,
            boolean charsetExplicit,
            ExpectOutputFilter outputFilter,
            ExpectTranscriptValues transcriptValues) {
        this.timeout = requirePositive(timeout, "timeout");
        if (transcriptLimit <= 0) {
            throw new IllegalArgumentException("transcriptLimit must be positive");
        }
        this.transcriptLimit = transcriptLimit;
        if (matchBufferLimit <= 0) {
            throw new IllegalArgumentException("matchBufferLimit must be positive");
        }
        this.matchBufferLimit = matchBufferLimit;
        this.charset = Objects.requireNonNull(charset, "charset");
        this.charsetExplicit = charsetExplicit;
        this.outputFilter = Objects.requireNonNull(outputFilter, "outputFilter");
        this.transcriptValues = Objects.requireNonNull(transcriptValues, "transcriptValues");
    }

    /**
     * Returns default expect options: match timeout 5 seconds, transcript limit 65,536 characters, match buffer
     * limit 65,536 characters, charset following the session charset (reported as UTF-8 by {@link #charset()}),
     * identity output filter, and {@link ExpectTranscriptValues#REDACTED} action values.
     *
     * @return default expect options
     */
    public static ExpectOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different default match timeout.
     *
     * @param timeout match timeout
     * @return updated options
     */
    public ExpectOptions withTimeout(Duration timeout) {
        return new ExpectOptions(
                timeout, transcriptLimit, matchBufferLimit, charset, charsetExplicit, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different transcript limit.
     *
     * @param transcriptLimit maximum retained transcript characters
     * @return updated options
     */
    public ExpectOptions withTranscriptLimit(int transcriptLimit) {
        return new ExpectOptions(
                timeout, transcriptLimit, matchBufferLimit, charset, charsetExplicit, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different stdout match buffer limit.
     *
     * @param matchBufferLimit maximum retained stdout match characters
     * @return updated options
     */
    public ExpectOptions withMatchBufferLimit(int matchBufferLimit) {
        return new ExpectOptions(
                timeout, transcriptLimit, matchBufferLimit, charset, charsetExplicit, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different output charset.
     *
     * <p>An explicitly configured charset takes precedence over the charset of the session the helper runs on.
     *
     * @param charset output charset
     * @return updated options
     */
    public ExpectOptions withCharset(Charset charset) {
        return new ExpectOptions(timeout, transcriptLimit, matchBufferLimit, charset, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different output filter.
     *
     * @param outputFilter output filter
     * @return updated options
     */
    public ExpectOptions withOutputFilter(ExpectOutputFilter outputFilter) {
        return new ExpectOptions(
                timeout, transcriptLimit, matchBufferLimit, charset, charsetExplicit, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different action transcript value policy.
     *
     * @param transcriptValues transcript value policy
     * @return updated options
     */
    public ExpectOptions withTranscriptValues(ExpectTranscriptValues transcriptValues) {
        return new ExpectOptions(
                timeout, transcriptLimit, matchBufferLimit, charset, charsetExplicit, outputFilter, transcriptValues);
    }

    /**
     * Returns the default match timeout.
     *
     * @return match timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the maximum retained transcript characters.
     *
     * @return transcript limit
     */
    public int transcriptLimit() {
        return transcriptLimit;
    }

    /**
     * Returns the maximum retained stdout match characters.
     *
     * @return match buffer limit
     */
    public int matchBufferLimit() {
        return matchBufferLimit;
    }

    /**
     * Returns the configured output charset.
     *
     * <p>Default options report UTF-8 here but follow the session charset at runtime; use
     * {@link #charsetFor(Charset)} to resolve the effective charset for a session.
     *
     * @return output charset
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Resolves the charset the expect helper uses on a session with the given charset.
     *
     * <p>Default options follow the session charset so expect reads stay consistent with session writes. A charset
     * configured explicitly through a constructor or {@link #withCharset(Charset)} takes precedence.
     *
     * @param sessionCharset charset of the session the helper runs on
     * @return effective expect charset
     */
    public Charset charsetFor(Charset sessionCharset) {
        Objects.requireNonNull(sessionCharset, "sessionCharset");
        return charsetExplicit ? charset : sessionCharset;
    }

    /**
     * Returns the output filter.
     *
     * @return output filter
     */
    public ExpectOutputFilter outputFilter() {
        return outputFilter;
    }

    /**
     * Returns whether action transcript entries include caller-provided values.
     *
     * @return transcript value policy
     */
    public ExpectTranscriptValues transcriptValues() {
        return transcriptValues;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
