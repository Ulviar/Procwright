/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Signals an Expect operation failure with a stable {@link Reason} and a bounded transcript snapshot.
 *
 * <p>A reason identifies what failed, not whether another operation is safe. In particular, {@link Reason#TIMEOUT}
 * may leave the handle open or close it after an abandoned regex evaluation; see {@link Expect}. The transcript can
 * contain unredacted child output even when action values use {@link ExpectTranscriptValues#REDACTED}.
 */
@SuppressWarnings("serial")
public final class ExpectException extends ProcwrightException {

    /** Failure reason. */
    private final Reason reason;

    /** Bounded transcript snapshot. */
    private final LineTranscript transcript;

    /**
     * Creates an expect exception.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param message failure message
     */
    public ExpectException(Reason reason, LineTranscript transcript, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    /**
     * Creates an expect exception with a cause.
     *
     * @param reason failure reason
     * @param transcript bounded transcript snapshot
     * @param message failure message
     * @param cause failure cause, or {@code null} when unavailable
     */
    public ExpectException(Reason reason, LineTranscript transcript, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    /**
     * Returns the failure reason.
     *
     * @return failure reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the bounded transcript captured at failure time.
     *
     * @return transcript snapshot
     */
    public LineTranscript transcript() {
        return transcript;
    }

    /**
     * Distinct expect failure reasons.
     */
    public enum Reason {
        /** Matcher access, output waiting, or regex evaluation exceeded the deadline; see {@link Expect} for retryability. */
        TIMEOUT,
        /** Process stdout reached EOF before expected output appeared. */
        EOF,
        /** Expect handle was closed before the operation could complete. */
        CLOSED,
        /** I/O, decoding, or another operation failed, including interruption; the cause carries additional detail. */
        FAILURE
    }
}
