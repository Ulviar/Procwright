package com.github.ulviar.icli.session;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Default policies for line-oriented request/response sessions.
 */
public final class LineSessionOptions {

    private static final int DEFAULT_MAX_LINE_CHARS = 1024 * 1024;
    private static final LineSessionOptions DEFAULTS = new LineSessionOptions(
            Duration.ofSeconds(5),
            64 * 1024,
            1024,
            DEFAULT_MAX_LINE_CHARS,
            StandardCharsets.UTF_8,
            ResponseDecoder.firstLine());

    private final Duration requestTimeout;
    private final int transcriptLimit;
    private final int stdoutBacklogLimit;
    private final int maxLineChars;
    private final Charset charset;
    private final ResponseDecoder responseDecoder;

    /**
     * Creates line-session options from explicit policies.
     *
     * @param requestTimeout default request timeout
     * @param transcriptLimit maximum retained transcript characters
     * @param stdoutBacklogLimit maximum pending stdout response lines
     * @param charset line protocol charset
     * @param responseDecoder default response decoder
     */
    public LineSessionOptions(
            Duration requestTimeout,
            int transcriptLimit,
            int stdoutBacklogLimit,
            Charset charset,
            ResponseDecoder responseDecoder) {
        this(requestTimeout, transcriptLimit, stdoutBacklogLimit, DEFAULT_MAX_LINE_CHARS, charset, responseDecoder);
    }

    /**
     * Creates line-session options from explicit policies.
     *
     * @param requestTimeout default request timeout
     * @param transcriptLimit maximum retained transcript characters
     * @param stdoutBacklogLimit maximum pending stdout response lines
     * @param maxLineChars maximum retained characters for one unterminated stdout line
     * @param charset line protocol charset
     * @param responseDecoder default response decoder
     */
    public LineSessionOptions(
            Duration requestTimeout,
            int transcriptLimit,
            int stdoutBacklogLimit,
            int maxLineChars,
            Charset charset,
            ResponseDecoder responseDecoder) {
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (transcriptLimit <= 0) {
            throw new IllegalArgumentException("transcriptLimit must be positive");
        }
        this.transcriptLimit = transcriptLimit;
        if (stdoutBacklogLimit <= 0) {
            throw new IllegalArgumentException("stdoutBacklogLimit must be positive");
        }
        this.stdoutBacklogLimit = stdoutBacklogLimit;
        if (maxLineChars <= 0) {
            throw new IllegalArgumentException("maxLineChars must be positive");
        }
        this.maxLineChars = maxLineChars;
        this.charset = Objects.requireNonNull(charset, "charset");
        this.responseDecoder = Objects.requireNonNull(responseDecoder, "responseDecoder");
    }

    /**
     * Returns default line-session options.
     *
     * @return default line-session options
     */
    public static LineSessionOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different default request timeout.
     *
     * @param requestTimeout request timeout
     * @return updated options
     */
    public LineSessionOptions withRequestTimeout(Duration requestTimeout) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLimit, maxLineChars, charset, responseDecoder);
    }

    /**
     * Returns a copy with a different transcript limit.
     *
     * @param transcriptLimit maximum retained transcript characters
     * @return updated options
     */
    public LineSessionOptions withTranscriptLimit(int transcriptLimit) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLimit, maxLineChars, charset, responseDecoder);
    }

    /**
     * Returns a copy with a different stdout backlog limit.
     *
     * @param stdoutBacklogLimit maximum pending stdout response lines
     * @return updated options
     */
    public LineSessionOptions withStdoutBacklogLimit(int stdoutBacklogLimit) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLimit, maxLineChars, charset, responseDecoder);
    }

    /**
     * Returns a copy with a different maximum stdout line length.
     *
     * @param maxLineChars maximum retained characters for one unterminated stdout line
     * @return updated options
     */
    public LineSessionOptions withMaxLineChars(int maxLineChars) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLimit, maxLineChars, charset, responseDecoder);
    }

    /**
     * Returns a copy with a different line protocol charset.
     *
     * @param charset line protocol charset
     * @return updated options
     */
    public LineSessionOptions withCharset(Charset charset) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLimit, maxLineChars, charset, responseDecoder);
    }

    /**
     * Returns a copy with a different response decoder.
     *
     * @param responseDecoder response decoder
     * @return updated options
     */
    public LineSessionOptions withResponseDecoder(ResponseDecoder responseDecoder) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLimit, maxLineChars, charset, responseDecoder);
    }

    /**
     * Returns the default request timeout.
     *
     * @return request timeout
     */
    public Duration requestTimeout() {
        return requestTimeout;
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
     * Returns the maximum pending stdout response lines.
     *
     * @return stdout backlog limit
     */
    public int stdoutBacklogLimit() {
        return stdoutBacklogLimit;
    }

    /**
     * Returns the maximum retained characters for one unterminated stdout line.
     *
     * @return maximum line characters
     */
    public int maxLineChars() {
        return maxLineChars;
    }

    /**
     * Returns the line protocol charset.
     *
     * @return charset
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Returns the default response decoder.
     *
     * @return response decoder
     */
    public ResponseDecoder responseDecoder() {
        return responseDecoder;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LineSessionOptions that)) {
            return false;
        }
        return transcriptLimit == that.transcriptLimit
                && stdoutBacklogLimit == that.stdoutBacklogLimit
                && maxLineChars == that.maxLineChars
                && requestTimeout.equals(that.requestTimeout)
                && charset.equals(that.charset)
                && responseDecoder.equals(that.responseDecoder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                requestTimeout, transcriptLimit, stdoutBacklogLimit, maxLineChars, charset, responseDecoder);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
