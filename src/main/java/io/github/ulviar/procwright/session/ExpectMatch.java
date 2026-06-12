/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.List;
import java.util.Objects;

/**
 * Result of one successful expect match.
 *
 * <p>Unlike transcripts, a match result returns live process output to the caller: the values are not redacted by
 * {@link ExpectTranscriptValues} because the caller explicitly asked for them. Do not log match results verbatim when
 * the automated prompt may contain secrets.
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
     * @param groups regex capture groups in declaration order; empty for literal matches
     * @param before output consumed before the match within the bounded match buffer
     */
    public ExpectMatch {
        Objects.requireNonNull(matched, "matched");
        groups = List.copyOf(groups);
        Objects.requireNonNull(before, "before");
    }
}
