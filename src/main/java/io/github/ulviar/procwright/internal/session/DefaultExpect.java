/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import io.github.ulviar.procwright.session.ExpectTranscriptValues;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Prompt-automation handle for a process launched in Expect output mode.
 *
 * <p>Matching is performed against decoded stdout, with optional built-in CSI stripping. Stderr is drained into the
 * transcript for diagnostics.
 */
public final class DefaultExpect implements Expect {

    private final DefaultSession session;
    private final ExpectSettings options;
    private final ExpectSessionState state;
    private final ExpectOutputTransport output;
    private final ExpectRegexMatcher regexMatcher;
    private final AtomicBoolean stdinCloseRequested = new AtomicBoolean();

    public DefaultExpect(DefaultSession session, ExpectSettings options) {
        this(session, options, ZeroReadBackoff.exponential(), PumpStarter.threading());
    }

    DefaultExpect(
            DefaultSession session, ExpectSettings options, ZeroReadBackoff zeroReadBackoff, PumpStarter pumpStarter) {
        this(session, options, zeroReadBackoff, pumpStarter, ExpectRegexMatcher::evaluate);
    }

    DefaultExpect(
            DefaultSession session,
            ExpectSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            ExpectRegexMatcher.Evaluator regexEvaluator) {
        this(session, options, zeroReadBackoff, pumpStarter, regexEvaluator, Threading::reportUncaught);
    }

    DefaultExpect(
            DefaultSession session,
            ExpectSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            ExpectRegexMatcher.Evaluator regexEvaluator,
            BiConsumer<Thread, Error> lateFatalFailureReporter) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        state = new ExpectSessionState(
                options.transcriptLimit(),
                options.matchBufferLimit(),
                Objects.requireNonNull(lateFatalFailureReporter, "lateFatalFailureReporter"));
        output = new ExpectOutputTransport(
                session, options, Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff"), state);
        regexMatcher = new ExpectRegexMatcher(state, regexEvaluator, this::closeAfterMatcherFailure);
        output.start(Objects.requireNonNull(pumpStarter, "pumpStarter"));
    }

    /**
     * Sends text without adding a line separator.
     *
     * @param text text to send
     * @return this helper
     */
    @Override
    public Expect send(String text) {
        Objects.requireNonNull(text, "text");
        beginOperation("Could not send expect text", "send: " + transcriptValue(text));
        try {
            session.send(text);
            return this;
        } catch (RuntimeException exception) {
            throw failInput("Could not send expect text", exception);
        } catch (Error error) {
            failFatalInput(error);
            throw error;
        }
    }

    /**
     * Sends text followed by a line feed.
     *
     * @param line line to send
     * @return this helper
     */
    @Override
    public Expect sendLine(String line) {
        requireLine(line);
        beginOperation("Could not send expect line", "send line: " + transcriptValue(line));
        try {
            session.sendLine(line);
            return this;
        } catch (RuntimeException exception) {
            throw failInput("Could not send expect line", exception);
        } catch (Error error) {
            failFatalInput(error);
            throw error;
        }
    }

    @Override
    public Expect sendSignal(TerminalSignal signal) {
        Objects.requireNonNull(signal, "signal");
        beginOperation("Could not send terminal signal", "send terminal signal: " + signal);
        try {
            session.sendSignal(signal);
            return this;
        } catch (RuntimeException exception) {
            throw failInput("Could not send terminal signal", exception);
        } catch (Error error) {
            failFatalInput(error);
            throw error;
        }
    }

    @Override
    public void closeStdin() {
        if (!stdinCloseRequested.compareAndSet(false, true)) {
            return;
        }
        beginOperation("Could not close expect stdin", "close stdin");
        try {
            session.closeStdin();
        } catch (RuntimeException exception) {
            throw failInput("Could not close expect stdin", exception);
        } catch (Error error) {
            failFatalInput(error);
            throw error;
        }
    }

    /**
     * Waits for literal text using the default timeout.
     *
     * @param text expected text
     * @return this helper
     */
    @Override
    public Expect expectText(String text) {
        return expectText(text, options.timeout());
    }

    /**
     * Waits for literal text.
     *
     * @param text expected text
     * @param timeout match timeout
     * @return this helper
     */
    @Override
    public Expect expectText(String text, Duration timeout) {
        expectTextMatch(text, timeout);
        return this;
    }

    /**
     * Waits for literal text using the default timeout and returns the match result.
     *
     * @param text expected text
     * @return match result
     */
    @Override
    public ExpectMatch expectTextMatch(String text) {
        return expectTextMatch(text, options.timeout());
    }

    /**
     * Waits for literal text and returns the match result.
     *
     * @param text expected text
     * @param timeout match timeout
     * @return match result
     */
    @Override
    public ExpectMatch expectTextMatch(String text, Duration timeout) {
        Objects.requireNonNull(text, "text");
        long deadlineNanos = deadline(timeout);
        String timeoutMessage = expectedMessage("Expected text not found", text);
        try {
            return state.awaitLiteral(text, deadlineNanos, timeoutMessage, "expect text: " + transcriptValue(text));
        } catch (ExpectException failure) {
            throw terminalizeObservedEof(failure);
        }
    }

    /**
     * Waits for a regular expression match using the default timeout.
     *
     * @param pattern expected pattern
     * @return this helper
     */
    @Override
    public Expect expectRegex(Pattern pattern) {
        return expectRegex(pattern, options.timeout());
    }

    /**
     * Waits for a regular expression match.
     *
     * @param pattern expected pattern
     * @param timeout match timeout
     * @return this helper
     */
    @Override
    public Expect expectRegex(Pattern pattern, Duration timeout) {
        expectRegexMatch(pattern, timeout);
        return this;
    }

    /**
     * Waits for a regular expression match using the default timeout and returns the match result.
     *
     * @param pattern expected pattern
     * @return match result
     */
    @Override
    public ExpectMatch expectRegexMatch(Pattern pattern) {
        return expectRegexMatch(pattern, options.timeout());
    }

    /**
     * Waits for a regular expression match and returns the match result.
     *
     * @param pattern expected pattern
     * @param timeout match timeout
     * @return match result
     */
    @Override
    public ExpectMatch expectRegexMatch(Pattern pattern, Duration timeout) {
        Objects.requireNonNull(pattern, "pattern");
        long deadlineNanos = deadline(timeout);
        String timeoutMessage = expectedMessage("Expected regex not found", pattern.pattern());
        try {
            return regexMatcher.match(
                    pattern, deadlineNanos, timeoutMessage, "expect regex: " + transcriptValue(pattern.pattern()));
        } catch (ExpectException failure) {
            throw terminalizeObservedEof(failure);
        }
    }

    /**
     * Returns the current bounded transcript snapshot.
     *
     * @return transcript snapshot
     */
    @Override
    public LineTranscript transcript() {
        return state.transcript();
    }

    @Override
    public CompletableFuture<SessionExit> onExit() {
        return session.onExit();
    }

    /**
     * Closes this helper and the underlying session. Calling this method more than once has no effect.
     */
    @Override
    public void close() {
        if (state.close()) {
            output.closeSession();
        }
    }

    private static long deadline(Duration timeout) {
        return DurationSupport.deadlineFromNow(DurationSupport.requirePositive(timeout, "timeout"));
    }

    private static String requireLine(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain line separators");
        }
        return line;
    }

    private static String printable(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private String transcriptValue(String text) {
        if (options.transcriptValues() == ExpectTranscriptValues.VERBATIM) {
            return printable(text);
        }
        return "<redacted>";
    }

    private String expectedMessage(String prefix, String expected) {
        return prefix + ": " + transcriptValue(expected);
    }

    private ExpectException failInput(String message, RuntimeException cause) {
        ExpectSessionState.InputFailureDecision decision = state.recordInputFailure(message, cause);
        if (decision.installed()) {
            output.closeSessionAfterFailure(decision.failure());
        }
        return decision.failure();
    }

    private void failFatalInput(Error failure) {
        if (state.recordFatalInputFailure(failure)) {
            output.closeSessionAfterFailure(failure);
        }
    }

    private void beginOperation(String unavailableMessage, String action) {
        try {
            state.beginOperation(unavailableMessage, action);
        } catch (ExpectException failure) {
            throw terminalizeObservedEof(failure);
        }
    }

    private ExpectException terminalizeObservedEof(ExpectException failure) {
        if (failure.reason() == ExpectException.Reason.EOF) {
            ExpectException selected = state.recordObservedEof(failure);
            if (selected.reason() == ExpectException.Reason.EOF) {
                output.closeSessionAfterObservedEof(selected);
            }
            return selected;
        }
        return failure;
    }

    private void closeAfterMatcherFailure(Throwable failure) {
        if (failure instanceof ExpectException expectFailure && expectFailure.reason() == ExpectException.Reason.EOF) {
            output.closeSessionAfterObservedEof(failure);
        } else {
            output.closeSessionAfterFailure(failure);
        }
    }
}
