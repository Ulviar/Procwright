/* SPDX-License-Identifier: Apache-2.0 */

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

    synchronized void addBytes(int count) {
        ensureBytesAvailable(count);
        bytes += count;
    }

    synchronized void addChars(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        chars = count > Long.MAX_VALUE - chars ? Long.MAX_VALUE : chars + count;
        if (chars > maxChars) {
            throw tooLarge("Protocol response exceeds maxResponseChars");
        }
    }

    synchronized void ensureBytesAvailable(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        if (count > maxBytes - bytes) {
            throw tooLarge("Protocol response exceeds maxResponseBytes");
        }
    }

    synchronized int remainingChars() {
        return chars >= maxChars ? 0 : (int) (maxChars - chars);
    }

    synchronized int remainingBytes() {
        return bytes >= maxBytes ? 0 : (int) (maxBytes - bytes);
    }

    synchronized void ensureCharacterBudgetOpen() {
        if (chars > maxChars) {
            throw tooLarge("Protocol response exceeds maxResponseChars");
        }
    }

    private ProtocolSessionException tooLarge(String message) {
        return failures.failure(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, message, null);
    }
}
