/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns line-session lifecycle and failure arbitration.
 *
 * <p>All transitions involving the active request and the terminal outcome are serialized by this
 * object. The atomic closed flag is the read-only fast path used by output pumps.
 */
final class LineSessionState {

    private final Supplier<LineTranscript> transcript;
    private final Consumer<Throwable> discardedFailure;
    private final Runnable sealFailureAttribution;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Request activeRequest;
    private TerminalSnapshot terminalOutcome;
    private boolean stdoutEof;
    private boolean failureAttributionSealed;

    LineSessionState(Supplier<LineTranscript> transcript) {
        this(transcript, ignored -> {}, () -> {});
    }

    LineSessionState(Supplier<LineTranscript> transcript, Consumer<Throwable> discardedFailure) {
        this(transcript, discardedFailure, () -> {});
    }

    LineSessionState(
            Supplier<LineTranscript> transcript,
            Consumer<Throwable> discardedFailure,
            Runnable sealFailureAttribution) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.discardedFailure = Objects.requireNonNull(discardedFailure, "discardedFailure");
        this.sealFailureAttribution = Objects.requireNonNull(sealFailureAttribution, "sealFailureAttribution");
    }

    boolean isClosed() {
        return closed.get();
    }

    synchronized Request beginRequest() {
        if (activeRequest != null) {
            throw new IllegalStateException("line request outcome is already active");
        }
        activeRequest = new Request();
        return activeRequest;
    }

    void completeRequest(Request request) {
        LineSessionException requestFailure;
        TerminalSnapshot sessionOutcome;
        synchronized (this) {
            requireActive(request);
            requestFailure = request.failure();
            sessionOutcome = terminalOutcome;
            if (requestFailure == null && sessionOutcome == null) {
                return;
            }
        }
        Error fatalError = fatalError(sessionOutcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (requestFailure != null) {
            throw requestFailure;
        }
        throw terminalException((FailureSnapshot) sessionOutcome);
    }

    private void endRequest(Request request) {
        boolean seal;
        synchronized (this) {
            if (activeRequest != request) {
                return;
            }
            activeRequest = null;
            seal = claimFailureAttributionSeal();
        }
        if (seal) {
            sealFailureAttribution.run();
        }
    }

    void recordStdoutEof() {
        boolean seal;
        synchronized (this) {
            stdoutEof = true;
            seal = claimFailureAttributionSeal();
        }
        if (seal) {
            sealFailureAttribution.run();
        }
    }

    synchronized boolean claimClose() {
        return !closed.getAndSet(true);
    }

    void markClosed() {
        closed.set(true);
    }

    synchronized TerminalSnapshot terminal() {
        return terminalOutcome;
    }

    TerminalSnapshot recordTerminalFailure(LineSessionException.Reason reason, String message, Throwable cause) {
        TerminalSelection selection = selectTerminalFailure(reason, message, cause);
        reportDiscarded(selection);
        return selection.selected();
    }

    TerminalSelection selectTerminalFailure(LineSessionException.Reason reason, String message, Throwable cause) {
        LineTranscript terminalTranscript = transcript.get();
        TerminalSnapshot selected;
        Throwable discarded = null;
        synchronized (this) {
            if (terminalOutcome == null) {
                terminalOutcome = new FailureSnapshot(reason, message, cause, terminalTranscript);
            } else if (terminalOutcome.primary() != cause) {
                discarded = cause;
            }
            if (activeRequest != null && terminalOutcome instanceof FailureSnapshot failure) {
                selectActiveTerminalFailure(activeRequest, terminalException(failure));
            }
            selected = terminalOutcome;
        }
        return new TerminalSelection(selected, discarded);
    }

    void reportDiscarded(TerminalSelection selection) {
        retainDiscarded(Objects.requireNonNull(selection, "selection").discarded());
    }

    TerminalSnapshot recordFatalError(Error error) {
        TerminalSnapshot selected;
        Throwable discarded = null;
        synchronized (this) {
            if (terminalOutcome == null) {
                terminalOutcome = new FatalSnapshot(error);
            } else if (terminalOutcome instanceof FatalSnapshot failure) {
                if (failure.error() != error) {
                    discarded = error;
                }
            } else {
                discarded = terminalOutcome.primary();
                terminalOutcome = new FatalSnapshot(error);
            }
            selected = terminalOutcome;
        }
        retainDiscarded(discarded);
        return selected;
    }

    LineSessionException recordRequestFailure(Request request, Supplier<LineSessionException> failureFactory) {
        LineSessionException candidate = Objects.requireNonNull(failureFactory.get(), "failure");
        return recordRequestFailure(request, candidate);
    }

    LineSessionException recordRequestTimeout(Request request) {
        synchronized (this) {
            LineSessionException current = request.failure();
            if (current != null && current.reason() == LineSessionException.Reason.TIMEOUT) {
                return current;
            }
        }
        return recordRequestFailure(request, timeout());
    }

    LineSessionException selectCallbackFailure(Request request, Supplier<LineSessionException> fallbackFactory) {
        synchronized (this) {
            Error fatalError = fatalError(terminalOutcome);
            if (fatalError != null) {
                throw fatalError;
            }
            LineSessionException selected = request.failure();
            if (selected != null) {
                return selected;
            }
        }
        LineSessionException candidate = Objects.requireNonNull(fallbackFactory.get(), "failure");
        RequestFailureResolution selected;
        Error fatal;
        synchronized (this) {
            fatal = fatalError(terminalOutcome);
            LineSessionException current = request.failure();
            if (fatal != null) {
                selected = new RequestFailureResolution(candidate, candidate);
            } else if (current != null) {
                selected = new RequestFailureResolution(current, candidate);
            } else {
                selected = recordRequestFailureLocked(request, candidate);
            }
        }
        retainDiscarded(selected.reportableFailure());
        if (fatal != null) {
            throw fatal;
        }
        return selected.returnedFailure();
    }

    LineSessionException releaseRetryablePreWrite(Request request, LineSessionException candidate) {
        boolean seal;
        FailureSnapshot terminalFailure;
        boolean sessionClosed;
        synchronized (this) {
            Error fatalError = fatalError(terminalOutcome);
            if (fatalError != null) {
                throw fatalError;
            }
            LineSessionException activeFailure = request.failure();
            if (activeFailure != null) {
                throw activeFailure;
            }
            terminalFailure = terminalOutcome instanceof FailureSnapshot failure ? failure : null;
            sessionClosed = closed.get();
            if (terminalFailure == null && !sessionClosed) {
                requireActive(request);
                activeRequest = null;
                seal = claimFailureAttributionSeal();
            } else {
                seal = false;
            }
        }
        if (terminalFailure != null) {
            LineSessionException selected = terminalException(terminalFailure);
            request.record(selected);
            throw selected;
        }
        if (sessionClosed) {
            LineSessionException selected = closed(null);
            request.record(selected);
            throw selected;
        }
        if (seal) {
            sealFailureAttribution.run();
        }
        return candidate;
    }

    LineSessionException arbitrateRequestAdmissionFailure(Supplier<LineSessionException> localFailure) {
        TerminalSnapshot outcome;
        boolean sessionClosed;
        synchronized (this) {
            outcome = terminalOutcome;
            sessionClosed = closed.get();
        }
        Error fatalError = fatalError(outcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (outcome instanceof FailureSnapshot failure) {
            throw terminalException(failure);
        }
        if (sessionClosed) {
            throw closed(null);
        }
        return Objects.requireNonNull(localFailure.get(), "localFailure");
    }

    void ensureOpen() {
        TerminalSnapshot outcome;
        synchronized (this) {
            if (!closed.get()) {
                return;
            }
            outcome = terminalOutcome;
        }
        Error fatalError = fatalError(outcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (outcome instanceof FailureSnapshot failure) {
            throw terminalException(failure);
        }
        throw closed(null);
    }

    LineSessionException primaryFailure(Request request, LineSessionException fallback) {
        LineSessionException primary = request.failure();
        return primary == null ? fallback : primary;
    }

    LineSessionException timeout() {
        return new LineSessionException(
                LineSessionException.Reason.TIMEOUT, transcript.get(), "Line request timed out");
    }

    LineSessionException eof() {
        return new LineSessionException(LineSessionException.Reason.EOF, transcript.get(), "Line session reached EOF");
    }

    LineSessionException closed(Throwable cause) {
        if (cause == null) {
            return new LineSessionException(
                    LineSessionException.Reason.CLOSED, transcript.get(), "Line session is closed");
        }
        return new LineSessionException(
                LineSessionException.Reason.CLOSED, transcript.get(), "Line session is closed", cause);
    }

    LineSessionException failure(String message, Throwable cause) {
        return new LineSessionException(LineSessionException.Reason.FAILURE, transcript.get(), message, cause);
    }

    LineSessionException failure(LineSessionException.Reason reason, String message, Throwable cause) {
        return new LineSessionException(reason, transcript.get(), message, cause);
    }

    private LineSessionException recordRequestFailure(Request request, LineSessionException candidate) {
        RequestFailureResolution selected;
        synchronized (this) {
            selected = recordRequestFailureLocked(request, candidate);
        }
        retainDiscarded(selected.reportableFailure());
        return selected.returnedFailure();
    }

    private RequestFailureResolution recordRequestFailureLocked(Request request, LineSessionException candidate) {
        if (candidate.reason() == LineSessionException.Reason.CLOSED) {
            LineSessionException selected = request.record(candidate);
            return selection(selected, candidate);
        }
        if (terminalOutcome == null) {
            LineSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(candidate);
            } else if (primary.reason() == LineSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(candidate);
            }
            terminalOutcome =
                    new FailureSnapshot(primary.reason(), primary.getMessage(), primary, primary.transcript());
            return selection(primary, candidate);
        }
        if (terminalOutcome instanceof FailureSnapshot failure) {
            LineSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(terminalException(failure));
            } else if (primary.reason() == LineSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(terminalException(failure));
            }
            return selection(primary, candidate);
        }
        return new RequestFailureResolution(candidate, candidate);
    }

    private static void selectActiveTerminalFailure(Request request, LineSessionException terminalFailure) {
        LineSessionException current = request.failure();
        if (current == null) {
            request.record(terminalFailure);
        } else if (current.reason() == LineSessionException.Reason.CLOSED) {
            request.replaceWithTerminal(terminalFailure);
        }
    }

    private LineSessionException terminalException(FailureSnapshot failure) {
        return new LineSessionException(
                failure.reason(),
                failure.transcript(),
                "Line session was closed by an earlier failure: " + failure.message(),
                failure.primary());
    }

    private static Error fatalError(TerminalSnapshot outcome) {
        return outcome instanceof FatalSnapshot failure ? failure.error() : null;
    }

    private void requireActive(Request request) {
        if (activeRequest != request) {
            throw new IllegalStateException("line request outcome is not active");
        }
    }

    private boolean claimFailureAttributionSeal() {
        if (failureAttributionSealed
                || !stdoutEof
                || activeRequest != null
                || terminalOutcome != null
                || closed.get()) {
            return false;
        }
        failureAttributionSealed = true;
        return true;
    }

    private static RequestFailureResolution selection(LineSessionException selected, LineSessionException candidate) {
        return new RequestFailureResolution(selected, selected == candidate ? null : candidate);
    }

    private void retainDiscarded(Throwable failure) {
        if (failure != null) {
            discardedFailure.accept(failure);
        }
    }

    sealed interface TerminalSnapshot permits FailureSnapshot, FatalSnapshot {

        Throwable primary();
    }

    record FailureSnapshot(
            LineSessionException.Reason reason, String message, Throwable primary, LineTranscript transcript)
            implements TerminalSnapshot {

        public FailureSnapshot {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(transcript, "transcript");
        }
    }

    record FatalSnapshot(Error error) implements TerminalSnapshot {

        public FatalSnapshot {
            Objects.requireNonNull(error, "error");
        }

        @Override
        public Throwable primary() {
            return error;
        }
    }

    record TerminalSelection(TerminalSnapshot selected, Throwable discarded) {

        public TerminalSelection {
            Objects.requireNonNull(selected, "selected");
        }
    }

    private record RequestFailureResolution(LineSessionException returnedFailure, Throwable reportableFailure) {

        private RequestFailureResolution {
            Objects.requireNonNull(returnedFailure, "returnedFailure");
        }
    }

    final class Request implements AutoCloseable {

        private final RequestFailureTracker<LineSessionException> failures = new RequestFailureTracker<>();

        LineSessionException record(LineSessionException failure) {
            return failures.record(failure);
        }

        LineSessionException failure() {
            return failures.failure();
        }

        LineSessionException replaceWithTerminal(LineSessionException failure) {
            return failures.replaceWithTerminal(failure);
        }

        void throwIfFailed() {
            failures.throwIfFailed();
        }

        @Override
        public void close() {
            endRequest(this);
        }
    }
}
