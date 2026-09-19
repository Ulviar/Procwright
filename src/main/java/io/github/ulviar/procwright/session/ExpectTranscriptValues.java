/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Controls whether Expect action transcript entries and expectation failure messages include caller-provided values.
 *
 * <p>This policy does not redact stdout or stderr, including secrets echoed by the child. It also does not redact
 * {@link ExpectMatch} values. Treat all retained process output as potentially sensitive.
 */
public enum ExpectTranscriptValues {
    /**
     * Replaces send and expect values in transcript action entries and expectation messages. This is the default.
     */
    REDACTED,

    /**
     * Includes send and expect values in transcript action entries and expectation messages.
     */
    VERBATIM
}
