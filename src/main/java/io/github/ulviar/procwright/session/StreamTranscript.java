/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Immutable bounded diagnostic snapshot of streaming stdout and stderr.
 *
 * <p>This labeled text is a retained suffix, not complete output. Retention counts UTF-16 code units, including labels;
 * ordering across streams reflects observation rather than the child's write order. Content is not redacted and may
 * contain secrets. The snapshot remains valid after the session closes.
 *
 * @param text retained diagnostic text
 * @param truncated true when older diagnostic text was discarded
 */
public record StreamTranscript(String text, boolean truncated) {

    /**
     * Validates a stream transcript snapshot.
     *
     * @param text retained diagnostic text
     * @param truncated true when older diagnostic text was discarded
     */
    public StreamTranscript {
        Objects.requireNonNull(text, "text");
    }
}
