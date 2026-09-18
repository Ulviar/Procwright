/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultExpect;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Prompt-automation handle opened by {@code command.interactive().expect().open()}.
 *
 * <p>Matching is performed against decoded stdout. The built-in ANSI option incrementally removes 7-bit ECMA-48 control
 * sequence introducer (CSI) sequences that begin with {@code ESC [}. Stderr is drained into the transcript for
 * diagnostics.
 *
 * <p>A timeout while waiting for new output or the serialized matcher slot leaves this handle open for another match.
 * If a regex evaluation is abandoned before it completes, the timeout is terminal: the process is stopped and no new
 * matcher task is admitted, even if the abandoned evaluation later returns. The {@code TIMEOUT} reason alone therefore
 * does not guarantee that a regex call can be retried. A concurrent handle close, output failure, or stdout EOF retains
 * its distinct failure reason instead of being reported as a timeout.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface.
 */
public sealed interface Expect extends AutoCloseable permits DefaultExpect {

    /**
     * Sends text without adding a line separator.
     *
     * @param text text to send
     * @return this handle
     */
    Expect send(String text);

    /**
     * Sends text followed by a line feed.
     *
     * @param line line to send
     * @return this handle
     */
    Expect sendLine(String line);

    /**
     * Writes the control byte represented by a terminal signal and flushes stdin.
     *
     * <p>With a PTY, the terminal driver may interpret the byte and deliver an operating-system signal to the process.
     * With ordinary pipes, this method only writes the byte to stdin; it does not signal the process.
     *
     * @param signal terminal signal
     * @return this handle
     */
    Expect sendSignal(TerminalSignal signal);

    /**
     * Logically closes process stdin for further writes without stopping the process.
     *
     * <p>After close work is admitted and started, the method returns without waiting for physical stream close or EOF
     * delivery. If close work cannot be admitted or started, the failure path performs bounded terminal cleanup before
     * this method throws. If an admitted close fails later while the process is still running, {@link #onExit()} completes
     * exceptionally with the original failure. Because that failure occurs after this method returns, it is not required
     * to be an {@link ExpectException}. Calling this method more than once has no effect.
     */
    void closeStdin();

    /**
     * Waits for literal text using the default timeout.
     *
     * @param text expected text
     * @return this handle
     */
    Expect expectText(String text);

    /**
     * Waits for literal text.
     *
     * @param text expected text
     * @param timeout match timeout
     * @return this handle
     */
    Expect expectText(String text, Duration timeout);

    /**
     * Waits for a regular expression match using the default timeout.
     *
     * @param pattern expected pattern
     * @return this handle
     */
    Expect expectRegex(Pattern pattern);

    /**
     * Waits for a regular expression match.
     *
     * @param pattern expected pattern
     * @param timeout match timeout
     * @return this handle
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
     * Returns an isolated view of the underlying process exit future.
     *
     * <p>The future completes after the process outcome and this handle's logical output processing settle. It does not
     * wait for a potentially blocking physical close of the process output streams; a later output close failure cannot
     * change the result. A terminal input or output failure completes the future exceptionally. A late stdin-close
     * failure follows the contract of {@link #closeStdin()}. EOF reported to a matcher stops a process that is still
     * live; an already selected natural process exit remains a normal result.
     *
     * @return process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Closes this handle and its process. Calling this method more than once has no effect.
     */
    @Override
    void close();
}
