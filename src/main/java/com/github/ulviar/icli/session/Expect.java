package com.github.ulviar.icli.session;

import com.github.ulviar.icli.internal.session.DefaultExpect;
import com.github.ulviar.icli.internal.session.SessionInternals;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Small expect-style prompt automation helper over a raw {@link Session}.
 *
 * <p>Matching is performed against filtered stdout. Stderr is drained into the transcript for diagnostics.
 *
 * <p>This is an iCLI-owned handle contract, not a service-provider interface. Expect helpers rely on iCLI's internal
 * output-ownership invariant and therefore support only sessions created by iCLI.
 */
public interface Expect extends AutoCloseable {

    /**
     * Creates an expect helper using default options.
     *
     * @param session session to automate
     * @return expect helper
     * @throws IllegalArgumentException when the session was not created by iCLI
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
     * @throws IllegalArgumentException when the session was not created by iCLI
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
