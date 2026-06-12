/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultExpect;
import io.github.ulviar.procwright.internal.session.SessionInternals;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Small expect-style prompt automation helper over a raw {@link Session}.
 *
 * <p>Matching is performed against filtered stdout. Stderr is drained into the transcript for diagnostics.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Expect helpers rely on
 * Procwright's internal output-ownership invariant and therefore support only sessions created by Procwright.
 */
public sealed interface Expect extends AutoCloseable permits DefaultExpect {

    /**
     * Creates an expect helper using default options.
     *
     * @param session session to automate
     * @return expect helper
     * @throws IllegalArgumentException when the session was not created by Procwright
     */
    static Expect on(Session session) {
        return on(session, ExpectOptions.defaults());
    }

    /**
     * Creates an expect helper using explicit options.
     *
     * @param session session to automate
     * @param options expect options
     * @return expect helper
     * @throws IllegalArgumentException when the session was not created by Procwright
     */
    static Expect on(Session session, ExpectOptions options) {
        return new DefaultExpect(SessionInternals.requireDefaultSession(session), options);
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
     * Closes this helper and the underlying session. Calling this method more than once has no effect.
     */
    @Override
    void close();
}
