/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import io.github.ulviar.procwright.session.ExpectTranscriptValues;
import io.github.ulviar.procwright.session.LineTranscript;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small expect-style prompt automation helper over a raw {@link Session}.
 *
 * <p>Matching is performed against decoded stdout, with optional built-in CSI stripping. Stderr is drained into the
 * transcript for diagnostics.
 */
public final class DefaultExpect implements Expect {

    private static final String OUTPUT_OWNER = "Expect";
    private static final int ZERO_READ_BACKOFF_STEPS = 8;

    private final DefaultSession session;
    private final ExpectSettings options;
    private final CharsetPolicy charsetPolicy;
    private final ZeroReadBackoff zeroReadBackoff;
    private final OutputPumpCoordinator outputPumps;
    private final BoundedTranscriptBuffer transcript;
    private final int matchBufferLimit;
    private final BoundedTaskRunner.Limiter regexLimiter;
    private final RegexEvaluator regexEvaluator;
    private final TerminalSelectionProbe terminalSelectionProbe;
    private final LateFatalFailureReporter lateFatalFailureReporter;
    private final BoundedTaskRunner.CancellationSignal terminalCancellation =
            new BoundedTaskRunner.CancellationSignal();
    private final BoundedMatchBuffer output;
    private final Object monitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean malformed = new AtomicBoolean();
    private final Set<Error> scheduledFatalOutputFailures = Collections.newSetFromMap(new IdentityHashMap<>());
    private long cursorOffset;
    private long cursorRevision;
    private Terminal terminal;
    private Throwable outputFailure;

    public DefaultExpect(DefaultSession session, ExpectSettings options) {
        this(session, options, ZeroReadBackoff.exponential(), PumpStarter.threading());
    }

    DefaultExpect(
            DefaultSession session, ExpectSettings options, ZeroReadBackoff zeroReadBackoff, PumpStarter pumpStarter) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                BoundedTaskRunner.REGEX_MATCHES,
                DefaultExpect::evaluateRegex);
    }

    DefaultExpect(
            DefaultSession session,
            ExpectSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            BoundedTaskRunner.Limiter regexLimiter,
            RegexEvaluator regexEvaluator) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                regexLimiter,
                regexEvaluator,
                () -> {},
                Threading::reportUncaught);
    }

    DefaultExpect(
            DefaultSession session,
            ExpectSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            BoundedTaskRunner.Limiter regexLimiter,
            RegexEvaluator regexEvaluator,
            TerminalSelectionProbe terminalSelectionProbe,
            LateFatalFailureReporter lateFatalFailureReporter) {
        this(
                session,
                options,
                zeroReadBackoff,
                pumpStarter,
                regexLimiter,
                regexEvaluator,
                terminalSelectionProbe,
                lateFatalFailureReporter,
                BoundedMatchBuffer.noWorkProbe());
    }

    DefaultExpect(
            DefaultSession session,
            ExpectSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            BoundedTaskRunner.Limiter regexLimiter,
            RegexEvaluator regexEvaluator,
            TerminalSelectionProbe terminalSelectionProbe,
            LateFatalFailureReporter lateFatalFailureReporter,
            BoundedMatchBuffer.WorkProbe matchBufferWorkProbe) {
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        // Reads match session writes by default; an explicit draft charset takes precedence.
        this.charsetPolicy = CharsetPolicy.replace(options.charsetFor(session.charset()));
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.outputPumps = new OutputPumpCoordinator(session, OUTPUT_OWNER);
        this.transcript = new BoundedTranscriptBuffer(options.transcriptLimit());
        this.matchBufferLimit = options.matchBufferLimit();
        this.output = new BoundedMatchBuffer(matchBufferLimit, matchBufferWorkProbe);
        this.regexLimiter = Objects.requireNonNull(regexLimiter, "regexLimiter");
        this.regexEvaluator = Objects.requireNonNull(regexEvaluator, "regexEvaluator");
        this.terminalSelectionProbe = Objects.requireNonNull(terminalSelectionProbe, "terminalSelectionProbe");
        this.lateFatalFailureReporter = Objects.requireNonNull(lateFatalFailureReporter, "lateFatalFailureReporter");
        startPumps(Objects.requireNonNull(pumpStarter, "pumpStarter"));
    }

    /**
     * Sends text without adding a line separator.
     *
     * @param text text to send
     * @return this helper
     */
    public Expect send(String text) {
        Objects.requireNonNull(text, "text");
        synchronized (monitor) {
            throwIfUnavailableAtOperationStart("Could not send expect text");
            transcript.appendAction("send: " + transcriptValue(text));
        }
        try {
            session.send(text);
            return this;
        } catch (RuntimeException exception) {
            throw failure("Could not send expect text", exception);
        }
    }

    /**
     * Sends text followed by a line feed.
     *
     * @param line line to send
     * @return this helper
     */
    public Expect sendLine(String line) {
        requireLine(line);
        synchronized (monitor) {
            throwIfUnavailableAtOperationStart("Could not send expect line");
            transcript.appendAction("send line: " + transcriptValue(line));
        }
        try {
            session.sendLine(line);
            return this;
        } catch (RuntimeException exception) {
            throw failure("Could not send expect line", exception);
        }
    }

    /**
     * Waits for literal text using the default timeout.
     *
     * @param text expected text
     * @return this helper
     */
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
    public ExpectMatch expectTextMatch(String text, Duration timeout) {
        Objects.requireNonNull(text, "text");
        long deadlineNanos = deadlineFromNow(requirePositive(timeout, "timeout"));
        String timeoutMessage = expectedMessage("Expected text not found", text);
        synchronized (monitor) {
            throwIfUnavailableAtOperationStart(timeoutMessage);
            transcript.appendAction("expect text: " + transcriptValue(text));
            BoundedMatchBuffer.LiteralMatcher matcher = output.literalMatcher(text);
            while (true) {
                throwIfTerminal(timeoutMessage);
                long searchStart = boundedCursorOffset();
                BoundedMatchBuffer.Match match = matcher.find(searchStart);
                if (match != null) {
                    String before = output.substring(searchStart, match.start());
                    cursorOffset = match.end();
                    cursorRevision++;
                    return new ExpectMatch(text, java.util.List.of(), before);
                }
                waitForMore(deadlineNanos, timeoutMessage);
            }
        }
    }

    /**
     * Waits for a regular expression match using the default timeout.
     *
     * @param pattern expected pattern
     * @return this helper
     */
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
    public ExpectMatch expectRegexMatch(Pattern pattern, Duration timeout) {
        Objects.requireNonNull(pattern, "pattern");
        long deadlineNanos = deadlineFromNow(requirePositive(timeout, "timeout"));
        String timeoutMessage = expectedMessage("Expected regex not found", pattern.pattern());
        synchronized (monitor) {
            throwIfUnavailableAtOperationStart(timeoutMessage);
            transcript.appendAction("expect regex: " + transcriptValue(pattern.pattern()));
        }
        AtomicReference<Thread> evaluatorThread = new AtomicReference<>();
        while (true) {
            RegexAttempt attempt;
            try {
                attempt = BoundedTaskRunner.run(
                        regexLimiter,
                        "procwright-expect-regex-",
                        deadlineNanos,
                        terminalCancellation,
                        this::handleLateRegexError,
                        () -> {
                            evaluatorThread.set(Thread.currentThread());
                            RegexSnapshot snapshot;
                            synchronized (monitor) {
                                snapshot = regexSnapshot();
                            }
                            RegexEvaluation evaluation =
                                    regexEvaluator.find(pattern, snapshot.output(), snapshot.searchStart());
                            return new RegexAttempt(snapshot, evaluation);
                        });
            } catch (TimeoutException exception) {
                synchronized (monitor) {
                    throw terminalFailureOrTimeout(timeoutMessage);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                synchronized (monitor) {
                    throwIfTerminal(timeoutMessage);
                }
                throw failure("Interrupted while matching expected output", exception);
            } catch (BoundedTaskRunner.TaskCancelledException exception) {
                synchronized (monitor) {
                    throw terminalFailureRequired(timeoutMessage);
                }
            } catch (ExecutionException exception) {
                throw arbitrateRegexFailure(
                        timeoutMessage,
                        Objects.requireNonNull(exception.getCause(), "regex failure cause"),
                        evaluatorThread.get());
            }

            RegexSnapshot snapshot = attempt.snapshot();
            RegexEvaluation evaluation = attempt.evaluation();
            synchronized (monitor) {
                throwIfTerminal(timeoutMessage);
                if (cursorRevision != snapshot.cursorRevision()) {
                    continue;
                }
                if (evaluation != null) {
                    cursorOffset = snapshot.outputOffset() + evaluation.end();
                    cursorRevision++;
                    String before = snapshot.output().substring(snapshot.searchStart(), evaluation.start());
                    return new ExpectMatch(evaluation.matched(), evaluation.groups(), before);
                }
                if (output.revision() != snapshot.outputRevision()) {
                    continue;
                }
                waitForMore(deadlineNanos, timeoutMessage);
            }
        }
    }

    static RegexEvaluation evaluateRegex(Pattern pattern, String text, int searchStart) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(searchStart, text.length());
        if (!matcher.find()) {
            return null;
        }
        return new RegexEvaluation(matcher.start(), matcher.end(), matcher.group(), groupsOf(matcher));
    }

    private static List<String> groupsOf(Matcher matcher) {
        ArrayList<String> groups = new ArrayList<>(matcher.groupCount());
        for (int index = 1; index <= matcher.groupCount(); index++) {
            String group = matcher.group(index);
            groups.add(group == null ? "" : group);
        }
        return groups;
    }

    /**
     * Returns the current bounded transcript snapshot.
     *
     * @return transcript snapshot
     */
    public LineTranscript transcript() {
        return lineTranscript();
    }

    /**
     * Closes this helper and the underlying session. Calling this method more than once has no effect.
     */
    @Override
    public void close() {
        boolean closeSession = false;
        boolean cancelMatchers = false;
        synchronized (monitor) {
            if (closed.compareAndSet(false, true)) {
                stopping.set(true);
                cancelMatchers = claimTerminalLocked(new Terminal(TerminalKind.CLOSED, null));
                monitor.notifyAll();
                closeSession = true;
            }
        }
        signalTerminal(cancelMatchers);
        if (closeSession) {
            outputPumps.closeSession();
        }
    }

    private void startPumps(PumpStarter pumpStarter) {
        outputPumps.start(
                pumpStarter,
                "procwright-expect-stdout-",
                stream -> pump("stdout", stream, true),
                "procwright-expect-stderr-",
                stream -> pump("stderr", stream, false),
                this::abortStartup);
    }

    private void pump(String streamName, InputStream stream, boolean matchable) {
        IncrementalTextDecoder decoder = null;
        try (stream) {
            int configuredLimit = matchable ? matchBufferLimit : options.transcriptLimit();
            decoder = new IncrementalTextDecoder(
                    charsetPolicy,
                    IncrementalTextDecoder.pendingByteLimitFor(configuredLimit),
                    IncrementalTextDecoder.outputWithoutInputLimitFor(configuredLimit));
            IncrementalTextDecoder activeDecoder = decoder;
            IncrementalAnsiControlSequenceStripper ansiStripper =
                    options.ansiControlSequenceStripping() ? new IncrementalAnsiControlSequenceStripper() : null;
            IncrementalTextDecoder.Sink sink = (chars, count) ->
                    publishDecoded(streamName, matchable, applyAnsiStripping(ansiStripper, chars, count));
            byte[] buffer = new byte[1024];
            int consecutiveZeroReads = 0;
            while (!stopping.get()) {
                int count = stream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    consecutiveZeroReads = Math.min(consecutiveZeroReads + 1, ZERO_READ_BACKOFF_STEPS);
                    if (!zeroReadBackoff.pause(consecutiveZeroReads, stopping::get)) {
                        return;
                    }
                    continue;
                }
                consecutiveZeroReads = 0;
                if (stopping.get()) {
                    return;
                }
                activeDecoder.decode(buffer, count, sink);
                malformed.compareAndSet(false, activeDecoder.malformed());
            }
            if (stopping.get()) {
                return;
            }
            activeDecoder.end(sink);
            malformed.compareAndSet(false, activeDecoder.malformed());
            if (ansiStripper != null) {
                publishDecoded(streamName, matchable, ansiStripper.finish());
            }
            if (matchable) {
                boolean cancelMatchers = false;
                synchronized (monitor) {
                    if (!stopping.get()) {
                        cancelMatchers = claimTerminalLocked(new Terminal(TerminalKind.EOF, null));
                        monitor.notifyAll();
                    }
                }
                signalTerminal(cancelMatchers);
            }
        } catch (Throwable throwable) {
            if (!closed.get() || throwable instanceof RuntimeException || throwable instanceof Error) {
                malformed.compareAndSet(false, decoder != null && decoder.malformed());
                failOutput(throwable);
            }
        }
    }

    private void publishDecoded(String streamName, boolean matchable, String decoded) {
        if (decoded.isEmpty()) {
            return;
        }
        synchronized (monitor) {
            if (stopping.get()) {
                return;
            }
            transcript.appendStream(streamName, decoded);
            if (matchable) {
                appendOutput(decoded);
                monitor.notifyAll();
            }
        }
    }

    private static String applyAnsiStripping(
            IncrementalAnsiControlSequenceStripper ansiStripper, char[] chars, int count) {
        String decoded = new String(chars, 0, count);
        return ansiStripper == null ? decoded : ansiStripper.append(decoded);
    }

    private void failOutput(Throwable failure) {
        boolean first;
        boolean cancelMatchers = false;
        Error fatalToPublish = null;
        Thread failureThread = Thread.currentThread();
        synchronized (monitor) {
            first = outputFailure == null;
            if (first) {
                outputFailure = failure;
                stopping.set(true);
                cancelMatchers = claimTerminalLocked(new Terminal(TerminalKind.FAILURE, failure));
            } else {
                SuppressionSupport.attach(outputFailure, failure);
            }
            if (failure instanceof Error error
                    && terminal != null
                    && terminal.kind() != TerminalKind.FAILURE
                    && scheduledFatalOutputFailures.add(error)) {
                fatalToPublish = error;
            }
            monitor.notifyAll();
        }
        signalTerminal(cancelMatchers);
        Error publicationFailure = fatalToPublish;
        Runnable publication = publicationFailure == null
                ? null
                : () -> lateFatalFailureReporter.report(failureThread, publicationFailure);
        if (first) {
            if (publication == null) {
                outputPumps.closeSessionPreserving(failure);
            } else {
                outputPumps.closeSessionPreserving(failure, publication);
            }
        } else if (publication != null) {
            outputPumps.publishAfterOutputCleanup(publication);
        }
    }

    private void abortStartup() {
        boolean cancelMatchers;
        synchronized (monitor) {
            stopping.set(true);
            closed.set(true);
            cancelMatchers = claimTerminalLocked(new Terminal(TerminalKind.CLOSED, null));
            monitor.notifyAll();
        }
        signalTerminal(cancelMatchers);
    }

    private void signalTerminal(boolean selected) {
        if (selected) {
            terminalSelectionProbe.afterSelection();
            terminalCancellation.cancel();
        }
    }

    private void waitForMore(long deadlineNanos, String message) {
        throwIfTerminal(message);

        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw timeout(message);
        }

        try {
            monitor.wait(Math.max(1, DurationSupport.remainingMillis(deadlineNanos)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("Interrupted while waiting for expected output", exception);
        }
    }

    private ExpectException terminalFailureOrTimeout(String message) {
        ExpectException failure = terminalFailure(message);
        return failure == null ? timeout(message) : failure;
    }

    private void throwIfTerminal(String message) {
        ExpectException failure = terminalFailure(message);
        if (failure != null) {
            throw failure;
        }
    }

    private ExpectException terminalFailure(String message) {
        if (terminal == null) {
            return null;
        }
        return switch (terminal.kind()) {
            case CLOSED -> closed();
            case FAILURE -> failure("Could not read expect output", terminal.cause());
            case EOF -> eof(message);
        };
    }

    private ExpectException timeout(String message) {
        return new ExpectException(ExpectException.Reason.TIMEOUT, lineTranscript(), message);
    }

    private ExpectException eof(String message) {
        return new ExpectException(ExpectException.Reason.EOF, lineTranscript(), message);
    }

    private ExpectException closed() {
        return new ExpectException(ExpectException.Reason.CLOSED, lineTranscript(), "Expect helper is closed");
    }

    private ExpectException failure(String message, Throwable cause) {
        return new ExpectException(ExpectException.Reason.FAILURE, lineTranscript(), message, cause);
    }

    private LineTranscript lineTranscript() {
        BoundedTranscriptBuffer.Snapshot snapshot = transcript.snapshot();
        return new LineTranscript(snapshot.text(), snapshot.truncated(), malformed.get());
    }

    private void appendOutput(String chunk) {
        output.append(chunk);
    }

    private long boundedCursorOffset() {
        return Math.max(cursorOffset, output.startOffset());
    }

    private RegexSnapshot regexSnapshot() {
        BoundedMatchBuffer.Snapshot snapshot = output.snapshot();
        int searchStart = Math.toIntExact(boundedCursorOffset() - snapshot.offset());
        return new RegexSnapshot(snapshot.text(), snapshot.offset(), searchStart, snapshot.revision(), cursorRevision);
    }

    private RuntimeException propagateRegexFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return failure("Could not match expected output", cause);
    }

    private RuntimeException arbitrateRegexFailure(String message, Throwable cause, Thread evaluatorThread) {
        RegexFailureResolution resolution;
        synchronized (monitor) {
            ExpectException selected = terminalFailure(message);
            if (selected == null) {
                return propagateRegexFailure(cause);
            }
            if (terminal.kind() == TerminalKind.FAILURE) {
                resolution = RegexFailureResolution.suppress(selected, terminal.cause(), cause);
            } else if (cause instanceof Error error) {
                resolution = RegexFailureResolution.report(
                        selected, Objects.requireNonNull(evaluatorThread, "regex evaluator thread"), error);
            } else {
                resolution = RegexFailureResolution.suppress(selected, selected, cause);
            }
        }
        if (resolution.suppressionTarget() != null) {
            SuppressionSupport.attach(resolution.suppressionTarget(), resolution.losingFailure());
        } else {
            lateFatalFailureReporter.report(resolution.reportThread(), resolution.reportedError());
        }
        return resolution.selectedFailure();
    }

    private void handleLateRegexError(Thread evaluatorThread, Error failure) {
        Throwable suppressionTarget;
        synchronized (monitor) {
            suppressionTarget = terminal != null && terminal.kind() == TerminalKind.FAILURE ? terminal.cause() : null;
        }
        if (suppressionTarget != null) {
            SuppressionSupport.attach(suppressionTarget, failure);
        } else {
            lateFatalFailureReporter.report(evaluatorThread, failure);
        }
    }

    private void throwIfUnavailableAtOperationStart(String message) {
        throwIfTerminal(message);
        if (closed.get()) {
            throw closed();
        }
    }

    private ExpectException terminalFailureRequired(String message) {
        ExpectException failure = terminalFailure(message);
        if (failure == null) {
            throw new IllegalStateException("expect matcher cancellation has no terminal owner");
        }
        return failure;
    }

    private boolean claimTerminalLocked(Terminal candidate) {
        if (terminal != null) {
            return false;
        }
        terminal = candidate;
        return true;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static long deadlineFromNow(Duration duration) {
        return DurationSupport.deadlineFromNow(duration);
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

    @FunctionalInterface
    interface RegexEvaluator {

        RegexEvaluation find(Pattern pattern, String text, int searchStart);
    }

    @FunctionalInterface
    interface TerminalSelectionProbe {

        void afterSelection();
    }

    @FunctionalInterface
    interface LateFatalFailureReporter {

        void report(Thread thread, Error failure);
    }

    record RegexEvaluation(int start, int end, String matched, List<String> groups) {

        RegexEvaluation {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid regex match range");
            }
            Objects.requireNonNull(matched, "matched");
            groups = List.copyOf(groups);
        }
    }

    private record RegexFailureResolution(
            ExpectException selectedFailure,
            Throwable suppressionTarget,
            Throwable losingFailure,
            Thread reportThread,
            Error reportedError) {

        private static RegexFailureResolution suppress(
                ExpectException selectedFailure, Throwable target, Throwable losingFailure) {
            return new RegexFailureResolution(selectedFailure, target, losingFailure, null, null);
        }

        private static RegexFailureResolution report(
                ExpectException selectedFailure, Thread reportThread, Error reportedError) {
            return new RegexFailureResolution(selectedFailure, null, null, reportThread, reportedError);
        }
    }

    private record Terminal(TerminalKind kind, Throwable cause) {

        private Terminal {
            Objects.requireNonNull(kind, "kind");
            if ((kind == TerminalKind.FAILURE) != (cause != null)) {
                throw new IllegalArgumentException("only a failure terminal carries a cause");
            }
        }
    }

    private enum TerminalKind {
        CLOSED,
        FAILURE,
        EOF
    }

    private record RegexSnapshot(
            String output, long outputOffset, int searchStart, long outputRevision, long cursorRevision) {}

    private record RegexAttempt(RegexSnapshot snapshot, RegexEvaluation evaluation) {
        private RegexAttempt {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
