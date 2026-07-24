/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Owns protocol-session lifecycle, active-request state, and failure arbitration.
 */
final class ProtocolSessionState implements ProtocolRuntimeFailures {

    private final Supplier<ProtocolTranscript> transcript;
    private final Supplier<OptionalInt> exitCode;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RequestOutcome activeRequest;
    private TerminalOutcome terminalOutcome;
    private boolean stdoutEof;

    ProtocolSessionState(Supplier<ProtocolTranscript> transcript, Supplier<OptionalInt> exitCode) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
    }

    boolean isClosed() {
        return closed.get();
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
        TerminalOutcome sessionOutcome;
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

    synchronized boolean endRequest(RequestOutcome request) {
        if (activeRequest != request) {
            return false;
        }
        activeRequest = null;
        return stdoutEof && terminalOutcome == null && !closed.get();
    }

    synchronized ProtocolSessionException finalizeProtocolFailure(
            RequestOutcome request, ProtocolSessionException fallback) {
        if (activeRequest == request) {
            activeRequest = null;
        }
        Error fatalError = fatalError(terminalOutcome);
        if (fatalError != null) {
            SuppressionSupport.attach(fatalError, fallback);
            throw fatalError;
        }
        return fallback;
    }

    synchronized Error finalizeFatalFailure(RequestOutcome request, Error fallback) {
        if (activeRequest == request) {
            activeRequest = null;
        }
        Error fatalError = fatalError(terminalOutcome);
        if (fatalError == null) {
            return fallback;
        }
        SuppressionSupport.attach(fatalError, fallback);
        return fatalError;
    }

    synchronized boolean recordStdoutEof() {
        stdoutEof = true;
        return activeRequest == null && terminalOutcome == null && !closed.get();
    }

    synchronized CloseDecision claimClose(boolean publishClosed) {
        boolean owner = !closed.getAndSet(true);
        if (owner && publishClosed && terminalOutcome == null) {
            terminalOutcome = ClosedTerminal.INSTANCE;
        }
        return new CloseDecision(owner, snapshot(terminalOutcome));
    }

    void markClosed() {
        closed.set(true);
    }

    synchronized TerminalSnapshot terminal() {
        return snapshot(terminalOutcome);
    }

    synchronized void ensureOpen() {
        if (terminalOutcome != null) {
            throwTerminalOutcome(terminalOutcome);
        }
        if (closed.get()) {
            throw closed(null);
        }
    }

    synchronized ProtocolSessionException arbitrateRequestAdmissionFailure(
            Supplier<ProtocolSessionException> localFailure) {
        if (terminalOutcome != null) {
            throwTerminalOutcome(terminalOutcome);
        }
        return Objects.requireNonNull(localFailure.get(), "localFailure");
    }

    synchronized TerminalSnapshot recordTerminalFailure(
            ProtocolSessionException.Reason reason, String message, Throwable cause) {
        if (terminalOutcome == null) {
            terminalOutcome = new TerminalFailure(reason, message, cause);
        } else if (!(terminalOutcome instanceof ClosedTerminal)) {
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
        } else if (terminalOutcome instanceof FatalTerminalFailure fatalFailure) {
            SuppressionSupport.attach(fatalFailure.error(), error);
        } else {
            if (terminalOutcome instanceof TerminalFailure failure) {
                SuppressionSupport.attach(error, failure.cause());
            }
            ProtocolSessionException activeFailure = activeRequest == null ? null : activeRequest.failure();
            SuppressionSupport.attach(error, activeFailure);
            terminalOutcome = new FatalTerminalFailure(error);
        }
        return snapshot(terminalOutcome);
    }

    synchronized ProtocolSessionException recordRequestFailure(
            RequestOutcome request, Supplier<ProtocolSessionException> failureFactory) {
        ProtocolSessionException candidate = Objects.requireNonNull(failureFactory.get(), "failure");
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
            terminalOutcome = new TerminalFailure(primary.reason(), primary.getMessage(), primary);
            return primary;
        }
        if (terminalOutcome instanceof TerminalFailure failure) {
            ProtocolSessionException primary = request.failure();
            if (primary == null) {
                primary = request.record(terminalException(failure));
            } else if (primary.reason() == ProtocolSessionException.Reason.CLOSED) {
                primary = request.replaceWithTerminal(terminalException(failure));
            }
            SuppressionSupport.attach(failure.cause(), candidate);
            return primary;
        }
        if (terminalOutcome instanceof ClosedTerminal) {
            ProtocolSessionException primary = request.failure();
            if (primary == null || primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                ProtocolSessionException closedFailure =
                        candidate.reason() == ProtocolSessionException.Reason.CLOSED ? candidate : closed(null);
                primary = request.replaceWithTerminal(closedFailure);
            } else {
                SuppressionSupport.attach(primary, candidate);
            }
            return primary;
        }
        FatalTerminalFailure fatalFailure = (FatalTerminalFailure) terminalOutcome;
        SuppressionSupport.attach(fatalFailure.error(), candidate);
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

    synchronized void attachLateCallbackFailure(RequestOutcome request, Throwable failure) {
        if (terminalOutcome instanceof FatalTerminalFailure fatalFailure) {
            SuppressionSupport.attach(fatalFailure.error(), failure);
            return;
        }
        ProtocolSessionException requestFailure = request.failure();
        if (requestFailure != null) {
            SuppressionSupport.attach(requestFailure, failure);
        } else if (terminalOutcome instanceof TerminalFailure terminalFailure) {
            SuppressionSupport.attach(terminalFailure.cause(), failure);
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

    private void throwTerminalOutcome(TerminalOutcome outcome) {
        Error fatalError = fatalError(outcome);
        if (fatalError != null) {
            throw fatalError;
        }
        if (outcome instanceof TerminalFailure failure) {
            throw terminalException(failure);
        }
        throw closed(null);
    }

    private ProtocolSessionException terminalException(TerminalFailure failure) {
        return new ProtocolSessionException(
                failure.reason(),
                transcript.get(),
                exitCode.get(),
                "Protocol session was closed by an earlier failure: " + failure.message(),
                failure.cause());
    }

    private static void selectActiveTerminalFailure(RequestOutcome request, ProtocolSessionException terminalFailure) {
        ProtocolSessionException current = request.failure();
        if (current == null) {
            request.record(terminalFailure);
        } else if (current.reason() == ProtocolSessionException.Reason.CLOSED) {
            request.replaceWithTerminal(terminalFailure);
        }
    }

    private static TerminalSnapshot snapshot(TerminalOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        if (outcome instanceof ClosedTerminal) {
            return new TerminalSnapshot(TerminalKind.CLOSED, null, null, null);
        }
        if (outcome instanceof FatalTerminalFailure failure) {
            return new TerminalSnapshot(TerminalKind.FATAL, null, failure.error(), failure.error());
        }
        TerminalFailure failure = (TerminalFailure) outcome;
        return new TerminalSnapshot(TerminalKind.FAILURE, failure.reason(), failure.cause(), null);
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

    record CloseDecision(boolean owner, TerminalSnapshot terminal) {}

    record TerminalSnapshot(
            TerminalKind kind, ProtocolSessionException.Reason reason, Throwable primary, Error fatalError) {

        TerminalSnapshot {
            Objects.requireNonNull(kind, "kind");
            if (kind == TerminalKind.FAILURE) {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(primary, "primary");
            }
            if (kind == TerminalKind.FATAL) {
                Objects.requireNonNull(primary, "primary");
                Objects.requireNonNull(fatalError, "fatalError");
            }
        }

        boolean isClosed() {
            return kind == TerminalKind.CLOSED;
        }
    }

    enum TerminalKind {
        FAILURE,
        FATAL,
        CLOSED
    }

    final class RequestOutcome {

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
    }

    private sealed interface TerminalOutcome permits TerminalFailure, FatalTerminalFailure, ClosedTerminal {}

    private enum ClosedTerminal implements TerminalOutcome {
        INSTANCE
    }

    private record TerminalFailure(ProtocolSessionException.Reason reason, String message, Throwable cause)
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
