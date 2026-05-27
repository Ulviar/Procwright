package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Default policies for generic protocol sessions.
 */
public final class ProtocolSessionOptions {

    private static final ProtocolSessionOptions DEFAULTS = new ProtocolSessionOptions(
            Duration.ofSeconds(5),
            64 * 1024,
            1024 * 1024,
            1024 * 1024,
            Integer.MAX_VALUE,
            1024 * 1024,
            Integer.MAX_VALUE,
            CharsetPolicy.replace(StandardCharsets.UTF_8));

    private final Duration requestTimeout;
    private final int transcriptLimit;
    private final int stdoutBacklogLimit;
    private final int maxRequestBytes;
    private final int maxRequestChars;
    private final int maxResponseBytes;
    private final int maxResponseChars;
    private final CharsetPolicy charsetPolicy;

    /**
     * Creates protocol-session options from explicit policies.
     *
     * @param requestTimeout default request timeout
     * @param transcriptLimit maximum retained transcript characters
     * @param stdoutBacklogLimit maximum pending output bytes per stream
     * @param maxRequestBytes maximum bytes one request may write
     * @param maxRequestChars maximum chars one request may write through text helpers
     * @param maxResponseBytes maximum bytes one response may read
     * @param maxResponseChars maximum chars one response may decode through text helpers
     * @param charsetPolicy protocol text charset policy
     */
    public ProtocolSessionOptions(
            Duration requestTimeout,
            int transcriptLimit,
            int stdoutBacklogLimit,
            int maxRequestBytes,
            int maxRequestChars,
            int maxResponseBytes,
            int maxResponseChars,
            CharsetPolicy charsetPolicy) {
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.transcriptLimit = requirePositive(transcriptLimit, "transcriptLimit");
        this.stdoutBacklogLimit = requirePositive(stdoutBacklogLimit, "stdoutBacklogLimit");
        this.maxRequestBytes = requirePositive(maxRequestBytes, "maxRequestBytes");
        this.maxRequestChars = requirePositive(maxRequestChars, "maxRequestChars");
        this.maxResponseBytes = requirePositive(maxResponseBytes, "maxResponseBytes");
        this.maxResponseChars = requirePositive(maxResponseChars, "maxResponseChars");
        this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
    }

    /**
     * Returns default protocol-session options.
     *
     * @return default options
     */
    public static ProtocolSessionOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different default request timeout.
     *
     * @param requestTimeout request timeout
     * @return updated options
     */
    public ProtocolSessionOptions withRequestTimeout(Duration requestTimeout) {
        return copy(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    /**
     * Returns a copy with a different transcript retention limit.
     *
     * @param transcriptLimit maximum retained transcript characters
     * @return updated options
     */
    public ProtocolSessionOptions withTranscriptLimit(int transcriptLimit) {
        return copy(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    /**
     * Returns a copy with a different pending output byte limit per stream.
     *
     * @param stdoutBacklogLimit maximum pending output bytes per stream
     * @return updated options
     */
    public ProtocolSessionOptions withStdoutBacklogLimit(int stdoutBacklogLimit) {
        return copy(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    /**
     * Returns a copy with a different pending output byte limit per stream.
     *
     * @param outputBacklogLimit maximum pending output bytes per stream
     * @return updated options
     */
    public ProtocolSessionOptions withOutputBacklogLimit(int outputBacklogLimit) {
        return withStdoutBacklogLimit(outputBacklogLimit);
    }

    /**
     * Returns a copy with a different request byte limit.
     *
     * @param maxRequestBytes maximum bytes one request may write
     * @return updated options
     */
    public ProtocolSessionOptions withMaxRequestBytes(int maxRequestBytes) {
        return copy(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    /**
     * Returns a copy with a different request character limit for text writes.
     *
     * @param maxRequestChars maximum chars one request may write through text helpers
     * @return updated options
     */
    public ProtocolSessionOptions withMaxRequestChars(int maxRequestChars) {
        return copy(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    /**
     * Returns a copy with a different response byte limit.
     *
     * @param maxResponseBytes maximum bytes one response may read
     * @return updated options
     */
    public ProtocolSessionOptions withMaxResponseBytes(int maxResponseBytes) {
        return copy(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    /**
     * Returns a copy with a different response character limit for text reads.
     *
     * @param maxResponseChars maximum chars one response may decode through text helpers
     * @return updated options
     */
    public ProtocolSessionOptions withMaxResponseChars(int maxResponseChars) {
        return copy(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    /**
     * Returns a copy with a different charset policy.
     *
     * @param charsetPolicy charset policy
     * @return updated options
     */
    public ProtocolSessionOptions withCharsetPolicy(CharsetPolicy charsetPolicy) {
        return copy(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
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
     * Returns maximum retained transcript characters.
     *
     * @return transcript limit
     */
    public int transcriptLimit() {
        return transcriptLimit;
    }

    /**
     * Returns maximum pending output bytes per stream.
     *
     * @return output backlog limit
     */
    public int stdoutBacklogLimit() {
        return stdoutBacklogLimit;
    }

    /**
     * Returns maximum pending output bytes per stream.
     *
     * @return output backlog limit
     */
    public int outputBacklogLimit() {
        return stdoutBacklogLimit;
    }

    /**
     * Returns maximum bytes one request may write.
     *
     * @return request byte limit
     */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /**
     * Returns maximum chars one request may write through text helpers.
     *
     * @return request character limit
     */
    public int maxRequestChars() {
        return maxRequestChars;
    }

    /**
     * Returns maximum bytes one response may read.
     *
     * @return response byte limit
     */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /**
     * Returns maximum chars one response may decode through text helpers.
     *
     * @return response character limit
     */
    public int maxResponseChars() {
        return maxResponseChars;
    }

    /**
     * Returns protocol text charset policy.
     *
     * @return charset policy
     */
    public CharsetPolicy charsetPolicy() {
        return charsetPolicy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProtocolSessionOptions that)) {
            return false;
        }
        return transcriptLimit == that.transcriptLimit
                && stdoutBacklogLimit == that.stdoutBacklogLimit
                && maxRequestBytes == that.maxRequestBytes
                && maxRequestChars == that.maxRequestChars
                && maxResponseBytes == that.maxResponseBytes
                && maxResponseChars == that.maxResponseChars
                && requestTimeout.equals(that.requestTimeout)
                && charsetPolicy.equals(that.charsetPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    private ProtocolSessionOptions copy(
            Duration requestTimeout,
            int transcriptLimit,
            int stdoutBacklogLimit,
            int maxRequestBytes,
            int maxRequestChars,
            int maxResponseBytes,
            int maxResponseChars,
            CharsetPolicy charsetPolicy) {
        return new ProtocolSessionOptions(
                requestTimeout,
                transcriptLimit,
                stdoutBacklogLimit,
                maxRequestBytes,
                maxRequestChars,
                maxResponseBytes,
                maxResponseChars,
                charsetPolicy);
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
