/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Objects;

/**
 * Immutable diagnostic text snapshot captured by line sessions or Expect.
 *
 * <p>Text contains labeled stdout/stderr output and, for Expect, action entries. It is a bounded suffix, not a complete
 * response or a byte-exact recording. The retention limit counts UTF-16 code units, including diagnostic labels.
 * Ordering between stdout and stderr reflects observation and does not establish the child's write order.
 *
 * <p>Process output is not redacted. Expect's {@link ExpectTranscriptValues} only controls caller-provided action
 * values; echoed credentials can still appear here. The snapshot remains valid after the session closes.
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
