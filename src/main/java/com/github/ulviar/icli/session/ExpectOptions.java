package com.github.ulviar.icli.session;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Default policies for expect-style prompt automation.
 */
public final class ExpectOptions {

    private static final ExpectOptions DEFAULTS = new ExpectOptions(
            Duration.ofSeconds(5),
            64 * 1024,
            64 * 1024,
            StandardCharsets.UTF_8,
            ExpectOutputFilter.identity(),
            ExpectTranscriptValues.REDACTED);

    private final Duration timeout;
    private final int transcriptLimit;
    private final int matchBufferLimit;
    private final Charset charset;
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
        this.outputFilter = Objects.requireNonNull(outputFilter, "outputFilter");
        this.transcriptValues = Objects.requireNonNull(transcriptValues, "transcriptValues");
    }

    /**
     * Returns default expect options.
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
        return new ExpectOptions(timeout, transcriptLimit, matchBufferLimit, charset, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different transcript limit.
     *
     * @param transcriptLimit maximum retained transcript characters
     * @return updated options
     */
    public ExpectOptions withTranscriptLimit(int transcriptLimit) {
        return new ExpectOptions(timeout, transcriptLimit, matchBufferLimit, charset, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different stdout match buffer limit.
     *
     * @param matchBufferLimit maximum retained stdout match characters
     * @return updated options
     */
    public ExpectOptions withMatchBufferLimit(int matchBufferLimit) {
        return new ExpectOptions(timeout, transcriptLimit, matchBufferLimit, charset, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different output charset.
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
        return new ExpectOptions(timeout, transcriptLimit, matchBufferLimit, charset, outputFilter, transcriptValues);
    }

    /**
     * Returns a copy with a different action transcript value policy.
     *
     * @param transcriptValues transcript value policy
     * @return updated options
     */
    public ExpectOptions withTranscriptValues(ExpectTranscriptValues transcriptValues) {
        return new ExpectOptions(timeout, transcriptLimit, matchBufferLimit, charset, outputFilter, transcriptValues);
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
     * Returns the output charset.
     *
     * @return output charset
     */
    public Charset charset() {
        return charset;
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
