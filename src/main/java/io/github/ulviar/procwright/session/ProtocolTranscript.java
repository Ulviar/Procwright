/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Immutable diagnostic snapshot of stdout and stderr from a protocol session.
 *
 * <p>This is a labeled, bounded text suffix, not raw protocol bytes or a response frame. Retention counts UTF-16
 * code units, including labels. Diagnostic decoding uses the protocol charset with replacement even when response
 * decoding is strict; malformed diagnostic bytes do not alone imply that the typed response was invalid.
 *
 * <p>Output is not redacted. Ordering across stdout and stderr reflects observation rather than a guaranteed child
 * write order. The snapshot remains valid after the session closes.
 *
 * @param text retained transcript text
 * @param truncated whether older transcript content was discarded
 * @param malformed whether output contained bytes that were malformed for transcript decoding
 */
public record ProtocolTranscript(String text, boolean truncated, boolean malformed) {

    /**
     * Creates a protocol transcript snapshot.
     *
     * @param text retained transcript text
     * @param truncated whether older transcript content was discarded
     * @param malformed whether output contained bytes that were malformed for transcript decoding
     */
    public ProtocolTranscript {
        Objects.requireNonNull(text, "text");
    }
}
