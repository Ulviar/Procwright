/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns protocol-session lifecycle, active-request state, and failure arbitration.
 */
final class ProtocolSessionState implements ProtocolRuntimeFailures {

    private final Supplier<ProtocolTranscript> transcript;
    private final Supplier<OptionalInt> exitCode;
    private final Consumer<Throwable> discardedFailure;
    private final Runnable sealFailureAttribution;
    private volatile boolean closed;
    private RequestOutcome activeRequest;
    private TerminalSnapshot terminalOutcome;
    private boolean stdoutEof;
    private boolean failureAttributionSealed;

    ProtocolSessionState(Supplier<ProtocolTranscript> transcript, Supplier<OptionalInt> exitCode) {
        this(transcript, exitCode, ignored -> {}, () -> {});
    }

    ProtocolSessionState(
            Supplier<ProtocolTranscript> transcript,
            Supplier<OptionalInt> exitCode,
            Consumer<Throwable> discardedFailure) {
        this(transcript, exitCode, discardedFailure, () -> {});
    }

    ProtocolSessionState(
            Supplier<ProtocolTranscript> transcript,
            Supplier<OptionalInt> exitCode,
            Consumer<Throwable> discardedFailure,
            Runnable sealFailureAttribution) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
        this.discardedFailure = Objects.requireNonNull(discardedFailure, "discardedFailure");
        this.sealFailureAttribution = Objects.requireNonNull(sealFailureAttribution, "sealFailureAttribution");
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

    Error selectFatalFailure(Error fallback) {
        Error fatalError;
        synchronized (this) {
            fatalError = fatalError(terminalOutcome);
        }
        if (fatalError == null) {
            return fallback;
        }
        return fatalError;
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

    CloseDecision claimClose(boolean publishClosed) {
        ProtocolSessionException closedFailure = publishClosed ? closed(null) : null;
        synchronized (this) {
            if (closed) {
                return AlreadyClosed.INSTANCE;
            }
            closed = true;
            if (!publishClosed) {
                return CloseSilently.INSTANCE;
            }
            if (terminalOutcome == null) {
                terminalOutcome = new ClosedSnapshot(closedFailure);
            }
            return new PublishTerminal(terminalOutcome);
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
        ProtocolTranscript terminalTranscript = transcript.get();
        OptionalInt terminalExitCode = exitCode.get();
        TerminalSnapshot selected;
        Throwable discarded = null;
        synchronized (this) {
            if (terminalOutcome == null) {
                terminalOutcome = new FailureSnapshot(reason, message, cause, terminalTranscript, terminalExitCode);
            } else if (primaryOrNull(terminalOutcome) != cause) {
                discarded = cause;
            }
            if (activeRequest != null && terminalOutcome instanceof FailureSnapshot failure) {
                selectActiveTerminalFailure(activeRequest, terminalExceptionFromSnapshot(failure));
            }
            selected = terminalOutcome;
        }
        retainDiscarded(discarded);
        return selected;
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
                discarded = primaryOrNull(terminalOutcome);
                terminalOutcome = new FatalSnapshot(error);
            }
            selected = terminalOutcome;
        }
        retainDiscarded(discarded);
        return selected;
    }

    ProtocolSessionException recordRequestFailure(
            RequestOutcome request, Supplier<ProtocolSessionException> failureFactory) {
        ProtocolSessionException candidate = Objects.requireNonNull(failureFactory.get(), "failure");
        RequestFailureResolution selected;
        synchronized (this) {
            selected = recordRequestFailureLocked(request, candidate);
        }
        retainDiscarded(selected.reportableFailure());
        return selected.returnedFailure();
    }

    private RequestFailureResolution recordRequestFailureLocked(
            RequestOutcome request, ProtocolSessionException candidate) {
        if (terminalOutcome == null) {
            if (candidate.reason() == ProtocolSessionException.Reason.CLOSED) {
                ProtocolSessionException selected = request.record(candidate);
                return selection(selected, candidate);
            }
            ProtocolSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(candidate);
            } else if (primary.reason() == ProtocolSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(candidate);
            }
            terminalOutcome = new FailureSnapshot(
                    primary.reason(), primary.getMessage(), primary, primary.transcript(), primary.exitCode());
            return selection(primary, candidate);
        }
        if (terminalOutcome instanceof FailureSnapshot failure) {
            ProtocolSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(terminalExceptionFromSnapshot(failure));
            } else if (primary.reason() == ProtocolSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(terminalExceptionFromSnapshot(failure));
            }
            return selection(primary, candidate);
        }
        if (terminalOutcome instanceof ClosedSnapshot closedSnapshot) {
            ProtocolSessionException primary = request.failure();
            if (primary == null || primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                ProtocolSessionException closedFailure = candidate.reason() == ProtocolSessionException.Reason.CLOSED
                        ? candidate
                        : closedSnapshot.failure();
                primary = request.replaceWithTerminal(closedFailure);
                return selection(primary, candidate);
            }
            return selection(primary, candidate);
        }
        return new RequestFailureResolution(candidate, candidate);
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
        return recordRequestFailure(request, request::timeoutFailure);
    }

    ProtocolSessionException recordRequestInterruption(
            RequestOutcome request, String message, InterruptedException cause) {
        return recordRequestFailure(request, () -> request.interruptionFailure(message, cause));
    }

    ProtocolSessionException selectCallbackCancellation(
            RequestOutcome request, BoundedTaskRunner.TaskCancelledException cancellation) {
        return recordRequestFailure(request, () -> request.cancellationFailure(cancellation));
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

    private static Throwable primaryOrNull(TerminalSnapshot outcome) {
        if (outcome instanceof FatalSnapshot failure) {
            return failure.error();
        }
        return outcome instanceof FailureSnapshot failure ? failure.primary() : null;
    }

    private static Error fatalError(TerminalSnapshot outcome) {
        return outcome instanceof FatalSnapshot failure ? failure.error() : null;
    }

    private boolean claimFailureAttributionSeal() {
        if (failureAttributionSealed || !stdoutEof || activeRequest != null || terminalOutcome != null || closed) {
            return false;
        }
        failureAttributionSealed = true;
        return true;
    }

    sealed interface CloseDecision permits PublishTerminal, CloseSilently, AlreadyClosed {}

    record PublishTerminal(TerminalSnapshot terminal) implements CloseDecision {

        public PublishTerminal {
            Objects.requireNonNull(terminal, "terminal");
        }
    }

    enum CloseSilently implements CloseDecision {
        INSTANCE
    }

    enum AlreadyClosed implements CloseDecision {
        INSTANCE
    }

    sealed interface TerminalSnapshot permits FailureSnapshot, FatalSnapshot, ClosedSnapshot {}

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
    }

    record ClosedSnapshot(ProtocolSessionException failure) implements TerminalSnapshot {

        ClosedSnapshot {
            Objects.requireNonNull(failure, "failure");
        }
    }

    private static RequestFailureResolution selection(
            ProtocolSessionException selected, ProtocolSessionException candidate) {
        return new RequestFailureResolution(selected, selected == candidate ? null : candidate);
    }

    private void retainDiscarded(Throwable failure) {
        if (failure != null) {
            discardedFailure.accept(failure);
        }
    }

    private record RequestFailureResolution(ProtocolSessionException returnedFailure, Throwable reportableFailure) {

        private RequestFailureResolution {
            Objects.requireNonNull(returnedFailure, "returnedFailure");
        }
    }

    final class RequestOutcome implements AutoCloseable {

        private final RequestFailureTracker<ProtocolSessionException> failures = new RequestFailureTracker<>();
        private final TimeoutException timeoutCause = new TimeoutException("Protocol request deadline elapsed");
        private final AtomicReference<ProtocolSessionException> timeoutFailure = new AtomicReference<>();
        private final AtomicReference<ProtocolSessionException> interruptionFailure = new AtomicReference<>();
        private final AtomicReference<ProtocolSessionException> cancellationFailure = new AtomicReference<>();

        private ProtocolSessionException timeoutFailure() {
            return canonicalFailure(timeoutFailure, () -> timeout(timeoutCause));
        }

        private ProtocolSessionException interruptionFailure(String message, InterruptedException cause) {
            return canonicalFailure(
                    interruptionFailure,
                    () -> ProtocolSessionState.this.failure(ProtocolSessionException.Reason.FAILURE, message, cause));
        }

        private ProtocolSessionException cancellationFailure(BoundedTaskRunner.TaskCancelledException cancellation) {
            return canonicalFailure(cancellationFailure, () -> closed(cancellation));
        }

        private ProtocolSessionException canonicalFailure(
                AtomicReference<ProtocolSessionException> reference, Supplier<ProtocolSessionException> factory) {
            ProtocolSessionException selected = reference.get();
            if (selected != null) {
                return selected;
            }
            ProtocolSessionException candidate = Objects.requireNonNull(factory.get(), "failure");
            reference.compareAndSet(null, candidate);
            return reference.get();
        }

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
