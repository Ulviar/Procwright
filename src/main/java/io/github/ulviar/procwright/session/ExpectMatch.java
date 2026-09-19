/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.List;
import java.util.Objects;

/**
 * Result of one successful expect match.
 *
 * <p>A match result returns process output without redaction. {@link ExpectTranscriptValues} controls caller-provided
 * action values in transcript entries only; it does not redact echoed secrets in output or in this result.
 * The capture-group list is copied and unmodifiable. Results remain valid after the handle closes.
 *
 * <p>{@code before} starts at the previous match cursor or the oldest retained output, whichever is later. It is not
 * necessarily all output since the previous match because the bounded match buffer may have discarded an older prefix.
 *
 * @param matched the full matched text
 * @param groups regex capture groups in declaration order; empty for literal matches. Groups that did not
 *     participate in the match are represented as empty strings.
 * @param before output consumed before the match within the bounded match buffer
 */
public record ExpectMatch(String matched, List<String> groups, String before) {

    /**
     * Creates an expect match result.
     *
     * @param matched the full matched text
     * @param groups regex capture groups in declaration order; empty for literal matches and never containing null
     * @param before output consumed before the match within the bounded match buffer
     */
    public ExpectMatch {
        Objects.requireNonNull(matched, "matched");
        groups = List.copyOf(groups);
        Objects.requireNonNull(before, "before");
    }
}
