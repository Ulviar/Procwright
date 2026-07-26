/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns protocol-session lifecycle and failure arbitration.
 *
 * <p>The active request is the attribution target for asynchronous output failures; caller
 * admission belongs to {@link SerializedRequestGate}.
 */
final class ProtocolSessionState implements ProtocolRuntimeFailures {

    private final Supplier<ProtocolTranscript> transcript;
    private final Supplier<OptionalInt> exitCode;
    private final Consumer<Throwable> discardedFailure;
    private volatile boolean closed;
    private RequestOutcome activeRequest;
    private TerminalSnapshot terminalOutcome;

    ProtocolSessionState(Supplier<ProtocolTranscript> transcript, Supplier<OptionalInt> exitCode) {
        this(transcript, exitCode, ignored -> {});
    }

    ProtocolSessionState(
            Supplier<ProtocolTranscript> transcript,
            Supplier<OptionalInt> exitCode,
            Consumer<Throwable> discardedFailure) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
        this.discardedFailure = Objects.requireNonNull(discardedFailure, "discardedFailure");
    }

    boolean isClosed() {
        return closed;
    }

    synchronized RequestOutcome beginRequest() {
        if (activeRequest != null) {
            throw new IllegalStateException("protocol request outcome is already active");
        }
        activeRequest = new RequestOutcome();
        return activeRequest;
    }

    void completeRequest(RequestOutcome request) {
        ProtocolSessionException requestFailure;
        TerminalSnapshot sessionOutcome;
        synchronized (this) {
            if (activeRequest != request) {
                throw new IllegalStateException("protocol request outcome is not active");
            }
            requestFailure = request.failure();
            sessionOutcome = terminalOutcome;
            activeRequest = null;
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
        throwTerminalOutcome(sessionOutcome);
    }

    private void endRequest(RequestOutcome request) {
        synchronized (this) {
            if (activeRequest != request) {
                return;
            }
            activeRequest = null;
        }
    }

    ProtocolSessionException selectProtocolFailure(ProtocolSessionException fallback) {
        Error fatalError;
        synchronized (this) {
            fatalError = fatalError(terminalOutcome);
        }
        if (fatalError != null) {
            throw fatalError;
        }
        return fallback;
    }

    ProtocolSessionException terminalException(TerminalSnapshot outcome) {
        if (outcome instanceof FailureSnapshot failure) {
            return terminalExceptionWithLatestExitCode(failure);
        }
        if (outcome instanceof ClosedSnapshot closedSnapshot) {
            return closedSnapshot.failure();
        }
        throw new IllegalArgumentException("Fatal outcome does not have a protocol exception");
    }

    CloseClaim claimClose(boolean publishClosed) {
        synchronized (this) {
            if (closed) {
                return CloseClaim.OBSERVER;
            }
            closed = true;
            if (!publishClosed) {
                return CloseClaim.SILENT_OWNER;
            }
            if (terminalOutcome != null) {
                return new CloseClaim(true, terminalOutcome);
            }
        }

        ProtocolSessionException closedFailure = closed(null);
        synchronized (this) {
            if (terminalOutcome == null) {
                terminalOutcome = new ClosedSnapshot(closedFailure);
            }
            return new CloseClaim(true, terminalOutcome);
        }
    }

    synchronized TerminalSnapshot terminal() {
        return terminalOutcome;
    }

    void ensureOpen() {
        TerminalSnapshot outcome;
        boolean sessionClosed;
        synchronized (this) {
            outcome = terminalOutcome;
            sessionClosed = closed;
        }
        if (outcome != null) {
            throwTerminalOutcome(outcome);
        }
        if (sessionClosed) {
            throw closed(null);
        }
    }

    ProtocolSessionException arbitrateRequestAdmissionFailure(Supplier<ProtocolSessionException> localFailure) {
        TerminalSnapshot outcome;
        synchronized (this) {
            outcome = terminalOutcome;
        }
        if (outcome != null) {
            throwTerminalOutcome(outcome);
        }
        return Objects.requireNonNull(localFailure.get(), "localFailure");
    }

    TerminalSnapshot recordTerminalFailure(ProtocolSessionException.Reason reason, String message, Throwable cause) {
        Selection selection = selectFailure(
                reason,
                message,
                cause,
                Objects.requireNonNull(transcript.get(), "transcript"),
                Objects.requireNonNull(exitCode.get(), "exitCode"),
                false);
        retainDiscarded(selection.discarded());
        return Objects.requireNonNull(selection.selected(), "selected");
    }

    OutputSelection recordOutputFailure(ProtocolSessionException.Reason reason, String message, Throwable cause) {
        Selection selection = selectFailure(
                reason,
                message,
                cause,
                Objects.requireNonNull(transcript.get(), "transcript"),
                Objects.requireNonNull(exitCode.get(), "exitCode"),
                true);
        retainDiscarded(selection.discarded());
        return new OutputSelection(selection.selected(), selection.rejectedAfterClose());
    }

    TerminalSnapshot recordFatalError(Error error) {
        Selection selection = selectFatal(error, false);
        retainDiscarded(selection.discarded());
        return Objects.requireNonNull(selection.selected(), "selected");
    }

    OutputSelection recordOutputFatalError(Error error) {
        Selection selection = selectFatal(error, true);
        retainDiscarded(selection.discarded());
        return new OutputSelection(selection.selected(), selection.rejectedAfterClose());
    }

    ProtocolSessionException recordRequestFailure(
            RequestOutcome request, Supplier<ProtocolSessionException> failureFactory) {
        ProtocolSessionException candidate = Objects.requireNonNull(failureFactory.get(), "failure");
        synchronized (this) {
            return recordRequestFailureLocked(request, candidate);
        }
    }

    private ProtocolSessionException recordRequestFailureLocked(
            RequestOutcome request, ProtocolSessionException candidate) {
        if (terminalOutcome == null) {
            if (candidate.reason() == ProtocolSessionException.Reason.CLOSED) {
                return request.record(candidate);
            }
            ProtocolSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(candidate);
            } else if (primary.reason() == ProtocolSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(candidate);
            }
            terminalOutcome = new FailureSnapshot(
                    primary.reason(), primary.getMessage(), primary, primary.transcript(), primary.exitCode());
            return primary;
        }
        if (terminalOutcome instanceof FailureSnapshot failure) {
            ProtocolSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(terminalExceptionFromSnapshot(failure));
            } else if (primary.reason() == ProtocolSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(terminalExceptionFromSnapshot(failure));
            }
            return primary;
        }
        if (terminalOutcome instanceof ClosedSnapshot closedSnapshot) {
            ProtocolSessionException primary = request.failure();
            if (primary == null || primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                ProtocolSessionException closedFailure = candidate.reason() == ProtocolSessionException.Reason.CLOSED
                        ? candidate
                        : closedSnapshot.failure();
                primary = request.replaceWithTerminal(closedFailure);
                return primary;
            }
            return primary;
        }
        return candidate;
    }

    ProtocolRuntimeFailures trackedFailures(RequestOutcome request) {
        return new ProtocolRuntimeFailures() {
            @Override
            public ProtocolSessionException timeout(Throwable cause) {
                return recordRequestTimeout(request);
            }

            @Override
            public ProtocolSessionException interrupted(String message, InterruptedException cause) {
                return recordRequestInterruption(request, message, cause);
            }

            @Override
            public ProtocolSessionException closed(Throwable cause) {
                return recordRequestFailure(request, () -> ProtocolSessionState.this.closed(cause));
            }

            @Override
            public ProtocolSessionException eof() {
                return recordRequestFailure(request, ProtocolSessionState.this::eof);
            }

            @Override
            public ProtocolSessionException processExited(OptionalInt selectedExitCode) {
                return recordRequestFailure(request, () -> ProtocolSessionState.this.processExited(selectedExitCode));
            }

            @Override
            public ProtocolSessionException failure(
                    ProtocolSessionException.Reason reason, String message, Throwable cause) {
                return recordRequestFailure(request, () -> ProtocolSessionState.this.failure(reason, message, cause));
            }
        };
    }

    ProtocolSessionException primaryFailure(RequestOutcome request, ProtocolSessionException fallback) {
        ProtocolSessionException primary = request.failure();
        return primary == null ? fallback : primary;
    }

    ProtocolSessionException recordRequestTimeout(RequestOutcome request) {
        return recordRequestFailure(request, () -> timeout(new TimeoutException("Protocol request deadline elapsed")));
    }

    ProtocolSessionException recordRequestInterruption(
            RequestOutcome request, String message, InterruptedException cause) {
        return recordRequestFailure(request, () -> failure(ProtocolSessionException.Reason.FAILURE, message, cause));
    }

    ProtocolSessionException selectCallbackCancellation(
            RequestOutcome request, BoundedTaskRunner.TaskCancelledException cancellation) {
        return recordRequestFailure(request, () -> closed(cancellation));
    }

    void selectCallbackAbandonment(RequestOutcome request, String interruptionMessage, Throwable cause) {
        if (cause instanceof TimeoutException) {
            recordRequestTimeout(request);
        } else if (cause instanceof InterruptedException interruption) {
            recordRequestInterruption(request, interruptionMessage, interruption);
        } else if (cause instanceof BoundedTaskRunner.TaskCancelledException cancellation) {
            selectCallbackCancellation(request, cancellation);
        } else {
            throw new IllegalArgumentException("Unsupported callback abandonment", cause);
        }
    }

    @Override
    public ProtocolSessionException timeout(Throwable cause) {
        return new ProtocolSessionException(
                ProtocolSessionException.Reason.TIMEOUT, transcript.get(), "Protocol request timed out", cause);
    }

    @Override
    public ProtocolSessionException interrupted(String message, InterruptedException cause) {
        return failure(ProtocolSessionException.Reason.FAILURE, message, cause);
    }

    @Override
    public ProtocolSessionException closed(Throwable cause) {
        return cause == null
                ? new ProtocolSessionException(
                        ProtocolSessionException.Reason.CLOSED, transcript.get(), "Protocol session is closed")
                : new ProtocolSessionException(
                        ProtocolSessionException.Reason.CLOSED, transcript.get(), "Protocol session is closed", cause);
    }

    @Override
    public ProtocolSessionException eof() {
        OptionalInt selectedExitCode = exitCode.get();
        return selectedExitCode.isPresent()
                ? processExited(selectedExitCode)
                : new ProtocolSessionException(
                        ProtocolSessionException.Reason.EOF,
                        transcript.get(),
                        OptionalInt.empty(),
                        "Protocol session reached EOF",
                        null);
    }

    @Override
    public ProtocolSessionException processExited(OptionalInt selectedExitCode) {
        return new ProtocolSessionException(
                ProtocolSessionException.Reason.PROCESS_EXITED,
                transcript.get(),
                selectedExitCode,
                "Protocol process exited before a complete response was read",
                null);
    }

    @Override
    public ProtocolSessionException failure(ProtocolSessionException.Reason reason, String message, Throwable cause) {
        return new ProtocolSessionException(reason, transcript.get(), exitCode.get(), message, cause);
    }

    private void throwTerminalOutcome(TerminalSnapshot outcome) {
        Error fatalError = fatalError(outcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (outcome instanceof FailureSnapshot failure) {
            throw terminalExceptionWithLatestExitCode(failure);
        }
        throw ((ClosedSnapshot) outcome).failure();
    }

    private ProtocolSessionException terminalExceptionWithLatestExitCode(FailureSnapshot failure) {
        OptionalInt latestExitCode = exitCode.get();
        return exception(failure, latestExitCode.isPresent() ? latestExitCode : failure.exitCode());
    }

    private static ProtocolSessionException terminalExceptionFromSnapshot(FailureSnapshot failure) {
        return exception(failure, failure.exitCode());
    }

    private static ProtocolSessionException exception(FailureSnapshot failure, OptionalInt selectedExitCode) {
        return new ProtocolSessionException(
                failure.reason(),
                failure.transcript(),
                selectedExitCode,
                "Protocol session was closed by an earlier failure: " + failure.message(),
                failure.primary());
    }

    private static void selectActiveTerminalFailure(RequestOutcome request, ProtocolSessionException terminalFailure) {
        ProtocolSessionException current = request.failure();
        if (current == null) {
            request.record(terminalFailure);
        } else if (current.reason() == ProtocolSessionException.Reason.CLOSED) {
            request.replaceWithTerminal(terminalFailure);
        }
    }

    private static Error fatalError(TerminalSnapshot outcome) {
        return outcome instanceof FatalSnapshot failure ? failure.error() : null;
    }

    private Selection selectFailure(
            ProtocolSessionException.Reason reason,
            String message,
            Throwable cause,
            ProtocolTranscript terminalTranscript,
            OptionalInt terminalExitCode,
            boolean rejectAfterClose) {
        TerminalSnapshot selected;
        Throwable discarded = null;
        boolean rejected = false;
        synchronized (this) {
            if (rejectAfterClose && closed) {
                selected = terminalOutcome;
                discarded = cause;
                rejected = true;
            } else {
                if (terminalOutcome == null) {
                    terminalOutcome = new FailureSnapshot(reason, message, cause, terminalTranscript, terminalExitCode);
                } else if (terminalOutcome.primary() != cause) {
                    discarded = cause;
                }
                if (activeRequest != null && terminalOutcome instanceof FailureSnapshot failure) {
                    selectActiveTerminalFailure(activeRequest, terminalExceptionFromSnapshot(failure));
                }
                selected = terminalOutcome;
            }
        }
        return new Selection(selected, discarded, rejected);
    }

    private Selection selectFatal(Error error, boolean rejectAfterClose) {
        TerminalSnapshot selected;
        Throwable discarded = null;
        boolean rejected = false;
        synchronized (this) {
            if (rejectAfterClose && closed) {
                selected = terminalOutcome;
                discarded = error;
                rejected = true;
            } else {
                if (terminalOutcome == null) {
                    terminalOutcome = new FatalSnapshot(error);
                } else if (terminalOutcome.primary() != error) {
                    discarded = error;
                }
                selected = terminalOutcome;
            }
        }
        return new Selection(selected, discarded, rejected);
    }

    record CloseClaim(boolean owner, TerminalSnapshot terminalToPublish) {

        private static final CloseClaim OBSERVER = new CloseClaim(false, null);
        private static final CloseClaim SILENT_OWNER = new CloseClaim(true, null);

        CloseClaim {
            if (!owner && terminalToPublish != null) {
                throw new IllegalArgumentException("Only the close owner may publish a terminal outcome");
            }
        }
    }

    sealed interface TerminalSnapshot permits FailureSnapshot, FatalSnapshot, ClosedSnapshot {

        Throwable primary();
    }

    record FailureSnapshot(
            ProtocolSessionException.Reason reason,
            String message,
            Throwable primary,
            ProtocolTranscript transcript,
            OptionalInt exitCode)
            implements TerminalSnapshot {

        public FailureSnapshot {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(transcript, "transcript");
            Objects.requireNonNull(exitCode, "exitCode");
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

    record ClosedSnapshot(ProtocolSessionException failure) implements TerminalSnapshot {

        ClosedSnapshot {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public Throwable primary() {
            return failure;
        }
    }

    record OutputSelection(TerminalSnapshot selected, boolean rejectedAfterClose) {}

    private void retainDiscarded(Throwable failure) {
        if (failure instanceof Error) {
            discardedFailure.accept(failure);
        }
    }

    private record Selection(TerminalSnapshot selected, Throwable discarded, boolean rejectedAfterClose) {}

    final class RequestOutcome implements AutoCloseable {

        private final RequestFailureTracker<ProtocolSessionException> failures = new RequestFailureTracker<>();

        ProtocolSessionException record(ProtocolSessionException failure) {
            return failures.record(failure);
        }

        ProtocolSessionException failure() {
            return failures.failure();
        }

        ProtocolSessionException replaceWithTerminal(ProtocolSessionException failure) {
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
