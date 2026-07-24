/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns line-session lifecycle and failure arbitration.
 *
 * <p>All transitions involving the active request and the terminal outcome are serialized by this
 * object. The atomic closed flag is the read-only fast path used by output pumps.
 */
final class LineSessionState {

    private final Supplier<LineTranscript> transcript;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RequestFailureTracker<LineSessionException> activeRequest;
    private TerminalOutcome terminalOutcome;
    private boolean stdoutEof;

    LineSessionState(Supplier<LineTranscript> transcript) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    boolean isClosed() {
        return closed.get();
    }

    synchronized RequestFailureTracker<LineSessionException> beginRequest() {
        if (activeRequest != null) {
            throw new IllegalStateException("line request outcome is already active");
        }
        activeRequest = new RequestFailureTracker<>();
        return activeRequest;
    }

    void completeRequest(RequestFailureTracker<LineSessionException> request) {
        LineSessionException requestFailure;
        TerminalOutcome sessionOutcome;
        synchronized (this) {
            requestFailure = request.failure();
            sessionOutcome = terminalOutcome;
            if (requestFailure == null && sessionOutcome == null) {
                activeRequest = null;
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
        throw terminalException((TerminalFailure) sessionOutcome);
    }

    synchronized boolean endRequest(RequestFailureTracker<LineSessionException> request) {
        if (activeRequest == request) {
            activeRequest = null;
        }
        return stdoutEof && terminalOutcome == null && !closed.get();
    }

    synchronized boolean recordStdoutEof() {
        stdoutEof = true;
        return activeRequest == null && terminalOutcome == null && !closed.get();
    }

    synchronized boolean claimClose() {
        return !closed.getAndSet(true);
    }

    void markClosed() {
        closed.set(true);
    }

    synchronized TerminalSnapshot terminal() {
        return snapshot(terminalOutcome);
    }

    synchronized TerminalSnapshot recordTerminalFailure(
            LineSessionException.Reason reason, String message, Throwable cause) {
        if (terminalOutcome == null) {
            terminalOutcome = new TerminalFailure(reason, message, cause);
        } else {
            SuppressionSupport.attach(primary(terminalOutcome), cause);
        }
        if (activeRequest != null && terminalOutcome instanceof TerminalFailure failure) {
            selectActiveTerminalFailure(activeRequest, terminalException(failure));
        }
        return snapshot(terminalOutcome);
    }

    synchronized TerminalSnapshot recordFatalError(Error error) {
        if (terminalOutcome == null) {
            terminalOutcome = new FatalTerminalFailure(error);
        } else {
            SuppressionSupport.attach(primary(terminalOutcome), error);
        }
        return snapshot(terminalOutcome);
    }

    synchronized LineSessionException recordRequestFailure(
            RequestFailureTracker<LineSessionException> request, Supplier<LineSessionException> failureFactory) {
        return recordRequestFailureLocked(request, Objects.requireNonNull(failureFactory.get(), "failure"));
    }

    synchronized LineSessionException recordRequestTimeout(RequestFailureTracker<LineSessionException> request) {
        LineSessionException selected = request.failure();
        if (selected != null && selected.reason() == LineSessionException.Reason.TIMEOUT) {
            return selected;
        }
        return recordRequestFailureLocked(request, timeout());
    }

    synchronized LineSessionException selectCallbackFailure(
            RequestFailureTracker<LineSessionException> request, Supplier<LineSessionException> fallbackFactory) {
        Error fatalError = fatalError(terminalOutcome);
        if (fatalError != null) {
            throw fatalError;
        }
        LineSessionException selected = request.failure();
        return selected != null
                ? selected
                : recordRequestFailureLocked(request, Objects.requireNonNull(fallbackFactory.get(), "failure"));
    }

    synchronized LineSessionException releaseRetryablePreWrite(
            RequestFailureTracker<LineSessionException> request, LineSessionException candidate) {
        Error fatalError = fatalError(terminalOutcome);
        if (fatalError != null) {
            throw fatalError;
        }
        LineSessionException activeFailure = request.failure();
        if (activeFailure != null) {
            throw activeFailure;
        }
        if (terminalOutcome instanceof TerminalFailure failure) {
            LineSessionException terminalFailure = terminalException(failure);
            request.record(terminalFailure);
            throw terminalFailure;
        }
        if (closed.get()) {
            LineSessionException closedFailure = closed(null);
            request.record(closedFailure);
            throw closedFailure;
        }
        if (activeRequest != request) {
            throw new IllegalStateException("line request outcome is no longer active");
        }
        activeRequest = null;
        return candidate;
    }

    synchronized LineSessionException arbitrateRequestAdmissionFailure(Supplier<LineSessionException> localFailure) {
        Error fatalError = fatalError(terminalOutcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (terminalOutcome instanceof TerminalFailure failure) {
            throw terminalException(failure);
        }
        if (closed.get()) {
            throw closed(null);
        }
        return Objects.requireNonNull(localFailure.get(), "localFailure");
    }

    synchronized void ensureOpen() {
        if (!closed.get()) {
            return;
        }
        Error fatalError = fatalError(terminalOutcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (terminalOutcome instanceof TerminalFailure failure) {
            throw terminalException(failure);
        }
        throw closed(null);
    }

    LineSessionException primaryFailure(
            RequestFailureTracker<LineSessionException> request, LineSessionException fallback) {
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

    private LineSessionException recordRequestFailureLocked(
            RequestFailureTracker<LineSessionException> request, LineSessionException candidate) {
        if (candidate.reason() == LineSessionException.Reason.CLOSED) {
            return request.record(candidate);
        }
        if (terminalOutcome == null) {
            LineSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(candidate);
            } else if (primary.reason() == LineSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(candidate);
            }
            terminalOutcome = new TerminalFailure(primary.reason(), primary.getMessage(), primary);
            return primary;
        }
        if (terminalOutcome instanceof TerminalFailure failure) {
            LineSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(terminalException(failure));
            } else if (primary.reason() == LineSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(terminalException(failure));
            }
            SuppressionSupport.attach(failure.cause(), candidate);
            return primary;
        }
        FatalTerminalFailure fatalFailure = (FatalTerminalFailure) terminalOutcome;
        SuppressionSupport.attach(fatalFailure.error(), candidate);
        return candidate;
    }

    private static void selectActiveTerminalFailure(
            RequestFailureTracker<LineSessionException> request, LineSessionException terminalFailure) {
        LineSessionException current = request.failure();
        if (current == null) {
            request.record(terminalFailure);
        } else if (current.reason() == LineSessionException.Reason.CLOSED) {
            request.replaceWithTerminal(terminalFailure);
        }
    }

    private LineSessionException terminalException(TerminalFailure failure) {
        return new LineSessionException(
                failure.reason(),
                transcript.get(),
                "Line session was closed by an earlier failure: " + failure.message(),
                failure.cause());
    }

    private static TerminalSnapshot snapshot(TerminalOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        if (outcome instanceof FatalTerminalFailure failure) {
            return new TerminalSnapshot(null, failure.error(), failure.error());
        }
        TerminalFailure failure = (TerminalFailure) outcome;
        return new TerminalSnapshot(failure.reason(), failure.cause(), null);
    }

    private static Throwable primary(TerminalOutcome outcome) {
        if (outcome instanceof FatalTerminalFailure failure) {
            return failure.error();
        }
        return ((TerminalFailure) outcome).cause();
    }

    private static Error fatalError(TerminalOutcome outcome) {
        return outcome instanceof FatalTerminalFailure failure ? failure.error() : null;
    }

    record TerminalSnapshot(LineSessionException.Reason reason, Throwable primary, Error fatalError) {

        TerminalSnapshot {
            Objects.requireNonNull(primary, "primary");
            if (fatalError == null) {
                Objects.requireNonNull(reason, "reason");
            }
        }

        boolean isFailure() {
            return fatalError == null;
        }
    }

    private sealed interface TerminalOutcome permits TerminalFailure, FatalTerminalFailure {}

    private record TerminalFailure(LineSessionException.Reason reason, String message, Throwable cause)
            implements TerminalOutcome {

        private TerminalFailure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(cause, "cause");
        }
    }

    private record FatalTerminalFailure(Error error) implements TerminalOutcome {

        private FatalTerminalFailure {
            Objects.requireNonNull(error, "error");
        }
    }
}
