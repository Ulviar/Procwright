/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Bounded text transcript captured by line-oriented workflows.
 *
 * @param text retained transcript text
 * @param truncated whether older transcript content was discarded
 * @param malformed whether output contained malformed or unmappable bytes
 */
public record LineTranscript(String text, boolean truncated, boolean malformed) {

    /**
     * Creates a line transcript snapshot.
     *
     * @param text retained transcript text
     * @param truncated whether older transcript content was discarded
     * @param malformed whether output contained malformed or unmappable bytes
     */
    public LineTranscript {
        Objects.requireNonNull(text, "text");
    }
}
