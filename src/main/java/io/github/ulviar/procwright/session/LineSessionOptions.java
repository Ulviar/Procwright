/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
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
            CharsetPolicy.replace(StandardCharsets.UTF_8),
            ResponseDecoder.firstLine());

    private final Duration requestTimeout;
    private final int transcriptLimit;
    private final int stdoutBacklogLines;
    private final int maxLineChars;
    private final CharsetPolicy charsetPolicy;
    private final ResponseDecoder responseDecoder;

    /**
     * Creates line-session options from explicit policies.
     *
     * @param requestTimeout default request timeout
     * @param transcriptLimit maximum retained transcript characters
     * @param stdoutBacklogLines maximum pending stdout response lines
     * @param charset line protocol charset
     * @param responseDecoder default response decoder
     */
    public LineSessionOptions(
            Duration requestTimeout,
            int transcriptLimit,
            int stdoutBacklogLines,
            Charset charset,
            ResponseDecoder responseDecoder) {
        this(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLines,
                DEFAULT_MAX_LINE_CHARS,
                CharsetPolicy.replace(charset),
                responseDecoder);
    }

    /**
     * Creates line-session options from explicit policies.
     *
     * @param requestTimeout default request timeout
     * @param transcriptLimit maximum retained transcript characters
     * @param stdoutBacklogLines maximum pending stdout response lines
     * @param maxLineChars maximum retained characters for one unterminated stdout line
     * @param charset line protocol charset
     * @param responseDecoder default response decoder
     */
    public LineSessionOptions(
            Duration requestTimeout,
            int transcriptLimit,
            int stdoutBacklogLines,
            int maxLineChars,
            Charset charset,
            ResponseDecoder responseDecoder) {
        this(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLines,
                maxLineChars,
                CharsetPolicy.replace(charset),
                responseDecoder);
    }

    /**
     * Creates line-session options from explicit policies.
     *
     * @param requestTimeout default request timeout
     * @param transcriptLimit maximum retained transcript characters
     * @param stdoutBacklogLines maximum pending stdout response lines
     * @param maxLineChars maximum retained characters for one unterminated stdout line
     * @param charsetPolicy line protocol charset policy
     * @param responseDecoder default response decoder
     */
    public LineSessionOptions(
            Duration requestTimeout,
            int transcriptLimit,
            int stdoutBacklogLines,
            int maxLineChars,
            CharsetPolicy charsetPolicy,
            ResponseDecoder responseDecoder) {
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        if (transcriptLimit <= 0) {
            throw new IllegalArgumentException("transcriptLimit must be positive");
        }
        this.transcriptLimit = transcriptLimit;
        if (stdoutBacklogLines <= 0) {
            throw new IllegalArgumentException("stdoutBacklogLines must be positive");
        }
        this.stdoutBacklogLines = stdoutBacklogLines;
        if (maxLineChars <= 0) {
            throw new IllegalArgumentException("maxLineChars must be positive");
        }
        this.maxLineChars = maxLineChars;
        this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        this.responseDecoder = Objects.requireNonNull(responseDecoder, "responseDecoder");
    }

    /**
     * Returns default line-session options: request timeout 5 seconds, transcript limit 65,536 characters, stdout
     * backlog 1024 pending lines, maximum line length 1,048,576 characters, charset policy
     * {@code CharsetPolicy.replace(UTF-8)}, and {@code ResponseDecoder.firstLine()}.
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
                requestTimeout, transcriptLimit, stdoutBacklogLines, maxLineChars, charsetPolicy, responseDecoder);
    }

    /**
     * Returns a copy with a different transcript limit.
     *
     * @param transcriptLimit maximum retained transcript characters
     * @return updated options
     */
    public LineSessionOptions withTranscriptLimit(int transcriptLimit) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLines, maxLineChars, charsetPolicy, responseDecoder);
    }

    /**
     * Returns a copy with a different stdout backlog limit.
     *
     * <p>The limit counts pending response lines, not bytes.
     *
     * @param stdoutBacklogLines maximum pending stdout response lines
     * @return updated options
     */
    public LineSessionOptions withStdoutBacklogLines(int stdoutBacklogLines) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLines, maxLineChars, charsetPolicy, responseDecoder);
    }

    /**
     * Returns a copy with a different maximum stdout line length.
     *
     * @param maxLineChars maximum retained characters for one unterminated stdout line
     * @return updated options
     */
    public LineSessionOptions withMaxLineChars(int maxLineChars) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLines, maxLineChars, charsetPolicy, responseDecoder);
    }

    /**
     * Returns a copy with a different line protocol charset.
     *
     * @param charset line protocol charset
     * @return updated options
     */
    public LineSessionOptions withCharset(Charset charset) {
        return new LineSessionOptions(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLines,
                maxLineChars,
                CharsetPolicy.replace(charset),
                responseDecoder);
    }

    /**
     * Returns a copy with a different line protocol charset policy.
     *
     * @param charsetPolicy line protocol charset policy
     * @return updated options
     */
    public LineSessionOptions withCharsetPolicy(CharsetPolicy charsetPolicy) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLines, maxLineChars, charsetPolicy, responseDecoder);
    }

    /**
     * Returns a copy with a different response decoder.
     *
     * @param responseDecoder response decoder
     * @return updated options
     */
    public LineSessionOptions withResponseDecoder(ResponseDecoder responseDecoder) {
        return new LineSessionOptions(
                requestTimeout, transcriptLimit, stdoutBacklogLines, maxLineChars, charsetPolicy, responseDecoder);
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
     * <p>The limit counts lines, not bytes.
     *
     * @return stdout backlog limit in lines
     */
    public int stdoutBacklogLines() {
        return stdoutBacklogLines;
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
        return charsetPolicy.charset();
    }

    /**
     * Returns the line protocol charset decoding policy.
     *
     * @return charset policy
     */
    public CharsetPolicy charsetPolicy() {
        return charsetPolicy;
    }

    /**
     * Returns the default response decoder.
     *
     * @return response decoder
     */
    public ResponseDecoder responseDecoder() {
        return responseDecoder;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
