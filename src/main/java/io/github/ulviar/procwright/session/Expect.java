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
 * diagnostics. Use try-with-resources to close the handle and its process.
 *
 * <p>Successful literal and regex matches advance a shared cursor to the end of the match. The next call searches
 * from that cursor, so matches do not overlap. Regex matching uses {@link java.util.regex.Matcher#find()} semantics,
 * rather than requiring the whole output to match. A zero-width match does not advance past its position and can match
 * again on the next call. Concurrent callers share this cursor; coordinate a multi-step dialogue in the application.
 *
 * <p>Only a bounded suffix of stdout remains available for matching. Old output can be evicted before a match is found;
 * text retained in {@link #transcript()} is not searchable history. Configure the match buffer on
 * {@link io.github.ulviar.procwright.ExpectScenario.Draft#withMatchBufferLimit(int)} for the expected prompt size.
 * The initial match timeout is five seconds and covers waiting for matcher access as well as matching.
 *
 * <p>A timeout while waiting for new output or the serialized matcher slot leaves this handle open for another match.
 * If a regex evaluation is abandoned before it completes, the timeout is terminal: the process is stopped and no new
 * matcher task is admitted, even if the abandoned evaluation later returns. The {@code TIMEOUT} reason alone therefore
 * does not guarantee that a regex call can be retried. A concurrent handle close, output failure, or stdout EOF retains
 * its distinct failure reason instead of being reported as a timeout.
 *
 * <p>For a command that writes {@code ready&gt; }, reads a reply line, and acknowledges it with {@code ok:}:
 * {@snippet file="io/github/ulviar/procwright/examples/ApiUsageExamples.java" region="expect"}
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface.
 */
public sealed interface Expect extends AutoCloseable permits DefaultExpect {

    /**
     * Sends text using the input charset and flushes stdin without adding a line separator.
     *
     * <p>Input writes are blocking; the match timeout does not bound them.
     *
     * @param text text to send
     * @return this handle
     * @throws ExpectException if the handle is unavailable or stdin cannot be written; input failure is terminal
     */
    Expect send(String text);

    /**
     * Sends text followed by a line feed using the input charset and flushes stdin.
     *
     * <p>Input writes are blocking; the match timeout does not bound them.
     *
     * @param line text containing neither CR nor LF; an empty line is allowed
     * @return this handle
     * @throws IllegalArgumentException if the line contains CR or LF
     * @throws ExpectException if the handle is unavailable or stdin cannot be written; input failure is terminal
     */
    Expect sendLine(String line);

    /**
     * Writes the control byte represented by a terminal signal and flushes stdin.
     *
     * <p>With a PTY, the terminal driver may interpret the byte and deliver an operating-system signal to the process.
     * With ordinary pipes, this method only writes the byte to stdin; it does not signal the process.
     *
     * <p>Input writes are blocking; the match timeout does not bound them.
     *
     * @param signal terminal signal
     * @return this handle
     * @throws ExpectException if the handle is unavailable or stdin cannot be written; input failure is terminal
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
     *
     * @throws ExpectException if the handle is unavailable or stdin close cannot be started
     */
    void closeStdin();

    /**
     * Waits for literal text using the default timeout.
     *
     * @param text expected text
     * @return this handle
     * @throws ExpectException if matching times out, the thread is interrupted, stdout ends, the handle closes, or I/O fails;
     *     retryability follows the class contract
     */
    Expect expectText(String text);

    /**
     * Waits for literal text.
     *
     * @param text expected text
     * @param timeout positive timeout for matcher access and this match
     * @return this handle
     * @throws IllegalArgumentException if the timeout is zero or negative
     * @throws ExpectException if matching times out, the thread is interrupted, stdout ends, the handle closes, or I/O fails;
     *     retryability follows the class contract
     */
    Expect expectText(String text, Duration timeout);

    /**
     * Waits for a regular expression match using the default timeout.
     *
     * @param pattern expected pattern
     * @return this handle
     * @throws ExpectException if matching times out, the thread is interrupted, stdout ends, the handle closes, or I/O fails;
     *     retryability follows the class contract
     */
    Expect expectRegex(Pattern pattern);

    /**
     * Waits for a regular expression match.
     *
     * @param pattern expected pattern
     * @param timeout positive timeout for matcher access and this match
     * @return this handle
     * @throws IllegalArgumentException if the timeout is zero or negative
     * @throws ExpectException if matching times out, the thread is interrupted, stdout ends, the handle closes, or I/O fails;
     *     retryability follows the class contract
     */
    Expect expectRegex(Pattern pattern, Duration timeout);

    /**
     * Waits for literal text using the default timeout and returns the match result.
     *
     * <p>The result carries unredacted process output. {@link ExpectTranscriptValues} only controls action values in
     * transcript entries; it does not redact process output or match results. See {@link ExpectMatch}.
     *
     * @param text expected text
     * @return match result with the matched text, empty groups, and the output consumed before the match
     * @throws ExpectException if matching times out, the thread is interrupted, stdout ends, the handle closes, or I/O fails;
     *     retryability follows the class contract
     */
    ExpectMatch expectTextMatch(String text);

    /**
     * Waits for literal text and returns the match result.
     *
     * <p>The result carries unredacted process output. {@link ExpectTranscriptValues} only controls action values in
     * transcript entries; it does not redact process output or match results. See {@link ExpectMatch}.
     *
     * @param text expected text
     * @param timeout positive timeout for matcher access and this match
     * @return match result with the matched text, empty groups, and the output consumed before the match
     * @throws IllegalArgumentException if the timeout is zero or negative
     * @throws ExpectException if matching times out, the thread is interrupted, stdout ends, the handle closes, or I/O fails;
     *     retryability follows the class contract
     */
    ExpectMatch expectTextMatch(String text, Duration timeout);

    /**
     * Waits for a regular expression match using the default timeout and returns the match result.
     *
     * <p>The result carries unredacted process output. {@link ExpectTranscriptValues} only controls action values in
     * transcript entries; it does not redact process output or match results. See {@link ExpectMatch}.
     *
     * @param pattern expected pattern
     * @return match result with the full match, capture groups, and the output consumed before the match
     * @throws ExpectException if matching times out, the thread is interrupted, stdout ends, the handle closes, or I/O fails;
     *     retryability follows the class contract
     */
    ExpectMatch expectRegexMatch(Pattern pattern);

    /**
     * Waits for a regular expression match and returns the match result.
     *
     * <p>The result carries unredacted process output. {@link ExpectTranscriptValues} only controls action values in
     * transcript entries; it does not redact process output or match results. See {@link ExpectMatch}.
     *
     * @param pattern expected pattern
     * @param timeout positive timeout for matcher access and this match
     * @return match result with the full match, capture groups, and the output consumed before the match
     * @throws IllegalArgumentException if the timeout is zero or negative
     * @throws ExpectException if matching times out, the thread is interrupted, stdout ends, the handle closes, or I/O fails;
     *     retryability follows the class contract
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
     * <p>Each call returns an independent view. Cancelling or completing it does not stop the process or change other
     * views. Keep synchronous completion actions short; use asynchronous continuations for blocking work.
     *
     * @return cancellation-isolated process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Closes this handle and its process. Calling this method more than once has no effect.
     */
    @Override
    void close();
}
