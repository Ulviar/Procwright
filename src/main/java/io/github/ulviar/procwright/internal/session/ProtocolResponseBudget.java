package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.util.Objects;

/**
 * Owns global byte and character limits for one protocol response.
 */
final class ProtocolResponseBudget {

    private final int maxBytes;
    private final int maxChars;
    private final ProtocolRuntimeFailures failures;
    private long bytes;
    private long chars;

    ProtocolResponseBudget(int maxBytes, int maxChars, ProtocolRuntimeFailures failures) {
        this.maxBytes = maxBytes;
        this.maxChars = maxChars;
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    void addBytes(int count) {
        bytes += count;
        if (bytes > maxBytes) {
            throw failures.failure(
                    ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                    "Protocol response exceeds maxResponseBytes",
                    null);
        }
    }

    void addChars(int count) {
        chars += count;
        if (chars > maxChars) {
            throw failures.failure(
                    ProtocolSessionException.Reason.RESPONSE_TOO_LARGE,
                    "Protocol response exceeds maxResponseChars",
                    null);
        }
    }
}
