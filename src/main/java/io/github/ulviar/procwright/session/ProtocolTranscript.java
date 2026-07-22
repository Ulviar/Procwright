/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Bounded transcript captured by protocol workflows.
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
