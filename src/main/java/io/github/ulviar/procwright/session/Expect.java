/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultExpect;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Small expect-style prompt automation helper over a raw {@link Session}.
 *
 * <p>Matching is performed against decoded stdout. The built-in ANSI option incrementally removes 7-bit ECMA-48 control
 * sequence introducer (CSI) sequences that begin with {@code ESC [}. Stderr is drained into the transcript for
 * diagnostics.
 *
 * <p>A match timeout is recoverable: it does not close this helper or its session, and a caller may perform another
 * match. A concurrent close, output failure, or stdout EOF retains its distinct failure reason instead of being reported
 * as a timeout.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Expect helpers rely on
 * Procwright's internal output-ownership invariant and therefore support only sessions created by Procwright.
 */
public sealed interface Expect extends AutoCloseable permits DefaultExpect {

    /**
     * Immutable, write-only configuration for opening an expect helper over an existing session.
     *
     * <p>Each {@code with*} method returns a new draft. Creating and configuring drafts does not claim process output;
     * {@link #open()} does.
     */
    interface Draft {
        /**
         * Sets the default match timeout.
         *
         * @param timeout positive match timeout
         * @return updated draft
         */
        Draft withTimeout(Duration timeout);

        /**
         * Sets the retained transcript character limit.
         *
         * @param transcriptLimit positive character limit
         * @return updated draft
         */
        Draft withTranscriptLimit(int transcriptLimit);

        /**
         * Sets the maximum retained text available to matchers.
         *
         * @param matchBufferLimit positive character limit
         * @return updated draft
         */
        Draft withMatchBufferLimit(int matchBufferLimit);

        /**
         * Overrides the session charset used to decode output.
         *
         * @param charset output charset
         * @return updated draft
         */
        Draft withCharset(Charset charset);

        /**
         * Removes 7-bit ECMA-48 control sequence introducer (CSI) sequences beginning with {@code ESC [} from stdout and
         * stderr before matching and transcript capture.
         *
         * <p>Stripping is incremental, so a sequence split across process-output reads is handled consistently. An
         * incomplete or overlong candidate is preserved as text instead of being retained without a bound.
         *
         * @return updated draft
         */
        Draft withAnsiControlSequenceStripping();

        /**
         * Selects whether transcript values are retained or redacted.
         *
         * @param transcriptValues transcript value policy
         * @return updated draft
         */
        Draft withTranscriptValues(ExpectTranscriptValues transcriptValues);

        /**
         * Atomically claims both session output streams and opens the helper.
         *
         * <p>Merely retrieving raw stream wrappers does not conflict with this operation. A prior effective raw stream
         * operation, another helper claim, or completed session cleanup does.
         *
         * @return newly opened expect helper
         * @throws IllegalStateException if session output is already in raw mode, owned by another helper, or closed by
         *     session cleanup
         */
        Expect open();
    }

    /**
     * Sends text without adding a line separator.
     *
     * @param text text to send
     * @return this helper
     */
    Expect send(String text);

    /**
     * Sends text followed by a line feed.
     *
     * @param line line to send
     * @return this helper
     */
    Expect sendLine(String line);

    /**
     * Waits for literal text using the default timeout.
     *
     * @param text expected text
     * @return this helper
     */
    Expect expectText(String text);

    /**
     * Waits for literal text.
     *
     * @param text expected text
     * @param timeout match timeout
     * @return this helper
     */
    Expect expectText(String text, Duration timeout);

    /**
     * Waits for a regular expression match using the default timeout.
     *
     * @param pattern expected pattern
     * @return this helper
     */
    Expect expectRegex(Pattern pattern);

    /**
     * Waits for a regular expression match.
     *
     * @param pattern expected pattern
     * @param timeout match timeout
     * @return this helper
     */
    Expect expectRegex(Pattern pattern, Duration timeout);

    /**
     * Waits for literal text using the default timeout and returns the match result.
     *
     * <p>The result carries live process output: unlike transcripts it is not redacted, because the caller asked for
     * it. See {@link ExpectMatch}.
     *
     * @param text expected text
     * @return match result with the matched text, empty groups, and the output consumed before the match
     */
    ExpectMatch expectTextMatch(String text);

    /**
     * Waits for literal text and returns the match result.
     *
     * <p>The result carries live process output: unlike transcripts it is not redacted, because the caller asked for
     * it. See {@link ExpectMatch}.
     *
     * @param text expected text
     * @param timeout match timeout
     * @return match result with the matched text, empty groups, and the output consumed before the match
     */
    ExpectMatch expectTextMatch(String text, Duration timeout);

    /**
     * Waits for a regular expression match using the default timeout and returns the match result.
     *
     * <p>The result carries live process output: unlike transcripts it is not redacted, because the caller asked for
     * it. See {@link ExpectMatch}.
     *
     * @param pattern expected pattern
     * @return match result with the full match, capture groups, and the output consumed before the match
     */
    ExpectMatch expectRegexMatch(Pattern pattern);

    /**
     * Waits for a regular expression match and returns the match result.
     *
     * <p>The result carries live process output: unlike transcripts it is not redacted, because the caller asked for
     * it. See {@link ExpectMatch}.
     *
     * @param pattern expected pattern
     * @param timeout match timeout
     * @return match result with the full match, capture groups, and the output consumed before the match
     */
    ExpectMatch expectRegexMatch(Pattern pattern, Duration timeout);

    /**
     * Returns the current bounded transcript snapshot.
     *
     * @return transcript snapshot
     */
    LineTranscript transcript();

    /**
     * Closes this helper and the underlying session. Output ownership is not returned to raw session code. Calling this
     * method more than once has no effect.
     */
    @Override
    void close();
}
