/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import io.github.ulviar.procwright.session.LineTranscript;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Owns expect output, cursor, transcript, and terminal-state arbitration.
 *
 * <p>All transitions that combine output visibility with the current match cursor or terminal outcome are serialized
 * by this object. The atomic flags are read-only fast paths for output pumps.
 */
final class ExpectSessionState {

    private final BoundedTranscriptBuffer transcript;
    private final BoundedMatchBuffer output;
    private final BiConsumer<Thread, Error> lateFatalFailureReporter;
    private final BoundedTaskRunner.CancellationSignal terminalCancellation =
            new BoundedTaskRunner.CancellationSignal();
    private final BoundedTaskRunner.CancellationToken terminalCancellationToken = terminalCancellation.token();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean malformed = new AtomicBoolean();
    private final Set<Error> scheduledFatalOutputFailures = Collections.newSetFromMap(new IdentityHashMap<>());

    private long cursorOffset;
    private long cursorRevision;
    private Terminal terminal;
    private Throwable outputFailure;

    ExpectSessionState(
            int transcriptLimit, int matchBufferLimit, BiConsumer<Thread, Error> lateFatalFailureReporter) {
        this(
                new BoundedTranscriptBuffer(transcriptLimit),
                new BoundedMatchBuffer(matchBufferLimit),
                lateFatalFailureReporter);
    }

    ExpectSessionState(
            BoundedTranscriptBuffer transcript,
            BoundedMatchBuffer output,
            BiConsumer<Thread, Error> lateFatalFailureReporter) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.output = Objects.requireNonNull(output, "output");
        this.lateFatalFailureReporter = Objects.requireNonNull(lateFatalFailureReporter, "lateFatalFailureReporter");
    }

    boolean isClosed() {
        return closed.get();
    }

    boolean isStopping() {
        return stopping.get();
    }

    void markMalformed(boolean detected) {
        malformed.compareAndSet(false, detected);
    }

    BoundedTaskRunner.CancellationToken terminalCancellationToken() {
        return terminalCancellationToken;
    }

    synchronized void beginOperation(String unavailableMessage, String action) {
        throwIfUnavailableAtOperationStart(unavailableMessage);
        transcript.appendAction(action);
    }

    synchronized ExpectMatch awaitLiteral(
            String text, long deadlineNanos, String timeoutMessage, String transcriptAction) {
        throwIfUnavailableAtOperationStart(timeoutMessage);
        transcript.appendAction(transcriptAction);
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

    synchronized RegexSnapshot regexSnapshot() {
        BoundedMatchBuffer.Snapshot snapshot = output.snapshot();
        int searchStart = Math.toIntExact(boundedCursorOffset() - snapshot.offset());
        return new RegexSnapshot(snapshot.text(), snapshot.offset(), searchStart, snapshot.revision(), cursorRevision);
    }

    synchronized ExpectMatch acceptRegexEvaluation(
            RegexSnapshot snapshot,
            ExpectRegexMatcher.Evaluation evaluation,
            long deadlineNanos,
            String timeoutMessage) {
        throwIfTerminal(timeoutMessage);
        if (cursorRevision != snapshot.cursorRevision()) {
            return null;
        }
        if (evaluation != null) {
            cursorOffset = snapshot.outputOffset() + evaluation.end();
            cursorRevision++;
            String before = snapshot.output().substring(snapshot.searchStart(), evaluation.start());
            return new ExpectMatch(evaluation.matched(), evaluation.groups(), before);
        }
        if (output.revision() != snapshot.outputRevision()) {
            return null;
        }
        waitForMore(deadlineNanos, timeoutMessage);
        return null;
    }

    synchronized void publishDecoded(String streamName, boolean matchable, String decoded) {
        if (decoded.isEmpty() || stopping.get()) {
            return;
        }
        transcript.appendStream(streamName, decoded);
        if (matchable) {
            output.append(decoded);
            notifyAll();
        }
    }

    void recordStdoutEof() {
        boolean selected = false;
        synchronized (this) {
            if (!stopping.get()) {
                selected = claimTerminalLocked(new Terminal(TerminalKind.EOF, null));
                notifyAll();
            }
        }
        signalTerminal(selected);
    }

    OutputFailureDecision recordOutputFailure(Throwable failure) {
        boolean first;
        boolean selected = false;
        Error fatalToPublish = null;
        synchronized (this) {
            first = outputFailure == null;
            if (first) {
                outputFailure = failure;
                stopping.set(true);
                selected = claimTerminalLocked(new Terminal(TerminalKind.FAILURE, failure));
            }
            if (failure instanceof Error error
                    && terminal != null
                    && (first ? terminal.kind() != TerminalKind.FAILURE : error != outputFailure)
                    && scheduledFatalOutputFailures.add(error)) {
                fatalToPublish = error;
            }
            notifyAll();
        }
        signalTerminal(selected);
        return new OutputFailureDecision(first, fatalToPublish);
    }

    boolean close() {
        boolean first = false;
        boolean selected = false;
        synchronized (this) {
            if (closed.compareAndSet(false, true)) {
                first = true;
                stopping.set(true);
                selected = claimTerminalLocked(new Terminal(TerminalKind.CLOSED, null));
                notifyAll();
            }
        }
        signalTerminal(selected);
        return first;
    }

    void abortStartup() {
        boolean selected;
        synchronized (this) {
            stopping.set(true);
            closed.set(true);
            selected = claimTerminalLocked(new Terminal(TerminalKind.CLOSED, null));
            notifyAll();
        }
        signalTerminal(selected);
    }

    synchronized void throwIfTerminal(String message) {
        ExpectException failure = terminalFailure(message);
        if (failure != null) {
            throw failure;
        }
    }

    synchronized ExpectException terminalFailureOrTimeout(String message) {
        ExpectException failure = terminalFailure(message);
        return failure == null ? timeout(message) : failure;
    }

    synchronized ExpectException terminalFailureRequired(String message) {
        ExpectException failure = terminalFailure(message);
        if (failure == null) {
            throw new IllegalStateException("expect matcher cancellation has no terminal owner");
        }
        return failure;
    }

    RuntimeException arbitrateRegexFailure(String message, Throwable cause, Thread evaluatorThread) {
        RegexFailureResolution resolution;
        synchronized (this) {
            ExpectException selected = terminalFailure(message);
            if (selected == null) {
                return propagateRegexFailure(cause);
            }
            if (terminal.kind() == TerminalKind.FAILURE) {
                resolution = RegexFailureResolution.retain(selected, cause);
            } else if (cause instanceof Error error) {
                resolution = RegexFailureResolution.report(
                        selected, Objects.requireNonNull(evaluatorThread, "regex evaluator thread"), error);
            } else {
                resolution = RegexFailureResolution.retain(selected, cause);
            }
        }
        resolution.apply(lateFatalFailureReporter);
        return resolution.selectedFailure();
    }

    void reportLateFatal(Thread failureThread, Error failure) {
        BoundedFailureReporter.shared()
                .execute(failureThread, () -> lateFatalFailureReporter.accept(failureThread, failure));
    }

    ExpectException failure(String message, Throwable cause) {
        return new ExpectException(ExpectException.Reason.FAILURE, transcript(), message, cause);
    }

    LineTranscript transcript() {
        BoundedTranscriptBuffer.Snapshot snapshot = transcript.snapshot();
        return new LineTranscript(snapshot.text(), snapshot.truncated(), malformed.get());
    }

    private synchronized void waitForMore(long deadlineNanos, String message) {
        throwIfTerminal(message);
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw timeout(message);
        }
        try {
            wait(Math.max(1, DurationSupport.remainingMillis(deadlineNanos)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("Interrupted while waiting for expected output", exception);
        }
    }

    private void throwIfUnavailableAtOperationStart(String message) {
        throwIfTerminal(message);
        if (closed.get()) {
            throw closed();
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
        return new ExpectException(ExpectException.Reason.TIMEOUT, transcript(), message);
    }

    private ExpectException eof(String message) {
        return new ExpectException(ExpectException.Reason.EOF, transcript(), message);
    }

    private ExpectException closed() {
        return new ExpectException(ExpectException.Reason.CLOSED, transcript(), "Expect helper is closed");
    }

    private long boundedCursorOffset() {
        return Math.max(cursorOffset, output.startOffset());
    }

    private boolean claimTerminalLocked(Terminal candidate) {
        if (terminal != null) {
            return false;
        }
        terminal = candidate;
        return true;
    }

    private void signalTerminal(boolean selected) {
        if (selected) {
            terminalCancellation.cancel();
        }
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

    record RegexSnapshot(String output, long outputOffset, int searchStart, long outputRevision, long cursorRevision) {}

    record OutputFailureDecision(boolean first, Error fatalToPublish) {}

    private record Terminal(TerminalKind kind, Throwable cause) {

        private Terminal {
            Objects.requireNonNull(kind, "kind");
            if ((kind == TerminalKind.FAILURE) != (cause != null)) {
                throw new IllegalArgumentException("only a failure terminal carries a cause");
            }
        }
    }

    private record RegexFailureResolution(
            ExpectException selectedFailure, Throwable retainedFailure, Thread reportThread, Error reportedError) {

        private static RegexFailureResolution retain(ExpectException selectedFailure, Throwable retainedFailure) {
            return new RegexFailureResolution(selectedFailure, retainedFailure, null, null);
        }

        private static RegexFailureResolution report(
                ExpectException selectedFailure, Thread reportThread, Error reportedError) {
            return new RegexFailureResolution(selectedFailure, null, reportThread, reportedError);
        }

        private void apply(BiConsumer<Thread, Error> reporter) {
            if (retainedFailure != null) {
                selectedFailure.addSuppressed(retainedFailure);
            } else {
                reporter.accept(reportThread, reportedError);
            }
        }
    }

    private enum TerminalKind {
        CLOSED,
        FAILURE,
        EOF
    }
}
