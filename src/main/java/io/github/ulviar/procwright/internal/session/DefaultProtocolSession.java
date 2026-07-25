/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.IOException;
import java.nio.charset.CoderMalfunctionError;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Generic request/response workflow over an interactive process.
 *
 * <p>The two output streams have asymmetric backlog semantics: stdout is the protocol stream, so
 * unread stdout beyond the backlog limit is an immediate typed session failure. Unread diagnostic
 * stderr remains nonfatal, but if its bounded queue overflows, a later stderr read fails atomically
 * instead of exposing output after dropped bytes.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class DefaultProtocolSession<I extends Object, O extends Object> implements ProtocolSession<I, O> {

    private static final String OUTPUT_OWNER = "ProtocolSession";
    private final DefaultSession session;
    private final ProtocolAdapter<I, O> adapter;
    private final ProtocolSessionSettings options;
    private final OutputPumpCoordinator outputPumps;
    private final ProtocolOutputTransport output;
    private final ProtocolCallbackRunner callbackRunner;
    private final SerializedRequestGate requestGate;
    private final ProtocolSessionState state;
    private final BoundedLifecyclePublisher.Permit exitPublication;
    private final CompletableFuture<SessionExit> exit = new CompletableFuture<>();
    private final BoundedTaskRunner.CancellationSignal callbackCancellation =
            new BoundedTaskRunner.CancellationSignal();

    public DefaultProtocolSession(
            DefaultSession session, ProtocolAdapter<I, O> adapter, ProtocolSessionSettings options) {
        this(session, adapter, options, Dependencies.defaults());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            Dependencies dependencies) {
        this.session = Objects.requireNonNull(session, "session");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.options = Objects.requireNonNull(options, "options");
        Dependencies runtime = Objects.requireNonNull(dependencies, "dependencies");
        this.callbackRunner = runtime.callbackRunner();
        this.requestGate = new SerializedRequestGate(runtime.requestLockWaiter());
        this.outputPumps = new OutputPumpCoordinator(
                session, OUTPUT_OWNER, OutputPumpCoordinator.FailureAttribution.SCENARIO_TERMINAL);
        int responsePendingByteLimit = ProtocolTextReader.pendingByteLimit(options);
        int responseOutputWithoutInputLimit = ProtocolTextReader.outputWithoutInputLimit(options);
        int decodedLineSuffixLimit = ProtocolTextReader.decodedLineSuffixLimit(options);
        ProtocolTranscriptBuffer initializedTranscript;
        ProtocolTextDecoderState stdoutDecoder;
        ProtocolTextDecoderState stderrDecoder;
        try {
            initializedTranscript = new ProtocolTranscriptBuffer(options.transcriptLimit(), options.charsetPolicy());
            stdoutDecoder = new ProtocolTextDecoderState(
                    options.charsetPolicy(),
                    responsePendingByteLimit,
                    responseOutputWithoutInputLimit,
                    decodedLineSuffixLimit);
            stderrDecoder = new ProtocolTextDecoderState(
                    options.charsetPolicy(),
                    responsePendingByteLimit,
                    responseOutputWithoutInputLimit,
                    decodedLineSuffixLimit);
        } catch (RuntimeException | CoderMalfunctionError exception) {
            ProtocolSessionException failure = new ProtocolSessionException(
                    ProtocolSessionException.Reason.DECODE_ERROR,
                    new ProtocolTranscript("", false, false),
                    "Could not initialize protocol output decoders",
                    exception);
            throw failure;
        } catch (Error error) {
            throw error;
        }
        this.state = new ProtocolSessionState(
                initializedTranscript::snapshot,
                this::exitCodeSnapshot,
                outputPumps::retainFailure,
                outputPumps::sealFailureAttribution);
        this.output = new ProtocolOutputTransport(
                options,
                state,
                runtime.zeroReadBackoff(),
                outputPumps,
                initializedTranscript,
                stdoutDecoder,
                stderrDecoder,
                runtime.outputNanoTime(),
                this::exitCodeSnapshot,
                new ProtocolOutputTransport.FailureHandler() {
                    @Override
                    public void closeTerminalPreserving(Throwable failure) {
                        DefaultProtocolSession.this.closeTerminalPreserving(failure);
                    }

                    @Override
                    public void closeQuietly(Throwable failure) {
                        DefaultProtocolSession.this.closeQuietly(failure);
                    }
                });
        BoundedLifecyclePublisher.Reservation publicationReservation =
                BoundedLifecyclePublisher.shared().reserve(1);
        this.exitPublication = publicationReservation.takePermit();
        try {
            output.start(runtime.pumpStarter());
            observeExitAfterOutputCleanup();
        } catch (RuntimeException | Error failure) {
            exitPublication.release();
            throw failure;
        }
    }

    @Override
    public O request(I request) {
        return request(request, options.requestTimeout());
    }

    @Override
    public O request(I request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        Duration requestTimeout = DurationSupport.requirePositive(timeout, "timeout");
        long deadlineNanos = DurationSupport.deadlineFromNow(requestTimeout);
        acquireRequestLock(deadlineNanos);
        try {
            return requestWhileLocked(request, deadlineNanos);
        } finally {
            requestGate.release();
        }
    }

    private O requestWhileLocked(I request, long deadlineNanos) {
        ProtocolSessionState.RequestOutcome requestOutcome = state.beginRequest();
        try (requestOutcome) {
            state.ensureOpen();
            writeRequest(request, deadlineNanos, requestOutcome);
            requestOutcome.throwIfFailed();
            O response = readResponse(deadlineNanos, requestOutcome);
            recordDeadlineFailure(deadlineNanos, requestOutcome);
            state.completeRequest(requestOutcome);
            return response;
        } catch (ProtocolSessionException exception) {
            ProtocolSessionException primary = state.primaryFailure(requestOutcome, exception);
            ProtocolSessionState.TerminalSnapshot outcome = state.terminal();
            if (primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                outcome = state.recordTerminalFailure(primary.reason(), primary.getMessage(), primary);
            }
            if (outcome instanceof ProtocolSessionState.FatalSnapshot fatal) {
                closePreserving(fatal.error());
            } else if (primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                closePreserving(primary);
            }
            throw state.selectProtocolFailure(primary);
        } catch (Error error) {
            ProtocolSessionState.TerminalSnapshot outcome = state.recordFatalError(error);
            Error selected = ((ProtocolSessionState.FatalSnapshot) outcome).error();
            closePreserving(selected);
            throw state.selectFatalFailure(selected);
        }
    }

    private void acquireRequestLock(long deadlineNanos) {
        try {
            if (!requestGate.acquireUntil(deadlineNanos)) {
                throw state.arbitrateRequestAdmissionFailure(() -> state.timeout(null));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.arbitrateRequestAdmissionFailure(() -> state.failure(
                    ProtocolSessionException.Reason.FAILURE,
                    "Interrupted while waiting to start protocol request",
                    exception));
        }
    }

    private void recordDeadlineFailure(long deadlineNanos, ProtocolSessionState.RequestOutcome requestOutcome) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            state.recordRequestTimeout(requestOutcome);
        }
    }

    @Override
    public ProtocolTranscript transcript() {
        return protocolTranscript();
    }

    @Override
    public CompletableFuture<SessionExit> onExit() {
        return exit.copy();
    }

    boolean exitCompleted() {
        return exit.isDone();
    }

    CompletableFuture<Void> physicalOutputCleanup() {
        return session.physicalOutputCleanup();
    }

    private void observeExitAfterOutputCleanup() {
        session.observeExit((result, failure) -> outputPumps.publishAfterOutputCleanup(
                () -> session.afterPhysicalOutputCleanup(() -> exitPublication.publish(() -> {
                    state.recordProcessExit(result == null ? exitCodeSnapshot() : result.exitCode());
                    if (failure == null) {
                        exit.complete(result);
                    } else {
                        exit.completeExceptionally(failure);
                    }
                }))));
    }

    @Override
    public void close() {
        closeWithEvent(true);
    }

    private void closeWithEvent(boolean publishClosed) {
        closeWithEvent(publishClosed, null);
    }

    private void closeWithEvent(boolean publishClosed, Throwable primary) {
        ProtocolSessionState.CloseDecision close = state.claimClose(publishClosed);
        boolean lifecycleOwner = !(close instanceof ProtocolSessionState.AlreadyClosed);
        if (lifecycleOwner) {
            callbackCancellation.cancel();
        }
        try {
            if (close instanceof ProtocolSessionState.PublishTerminal publication) {
                output.publishTerminal(publication.terminal());
            }
        } finally {
            if (primary != null) {
                outputPumps.closeSessionPreserving(primary);
            } else if (lifecycleOwner) {
                outputPumps.closeSession();
            }
        }
    }

    private void writeRequest(I request, long deadlineNanos, ProtocolSessionState.RequestOutcome requestOutcome) {
        ProtocolRuntimeFailures trackedFailures = state.trackedFailures(requestOutcome);
        RequestCapabilityScope capabilityScope = new RequestCapabilityScope("ProtocolWriter");
        ProtocolRequestWriter writer =
                new ProtocolRequestWriter(session, options, deadlineNanos, trackedFailures, capabilityScope);
        try {
            callbackRunner.run(
                    "procwright-protocol-stdin-",
                    deadlineNanos,
                    callbackCancellation,
                    this::failLateCallbackFailure,
                    failure -> {
                        capabilityScope.invalidate();
                        state.selectCallbackAbandonment(
                                requestOutcome, "Interrupted while writing protocol request", failure);
                    },
                    () -> {
                        state.ensureOpen();
                        capabilityScope.activate();
                        try {
                            adapter.writeRequest(request, writer);
                        } catch (Throwable callbackFailure) {
                            writer.throwIfError();
                            throw callbackFailure;
                        } finally {
                            capabilityScope.invalidate();
                        }
                        writer.throwIfFailed();
                        return null;
                    });
        } catch (TimeoutException exception) {
            throw state.recordRequestTimeout(requestOutcome);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw recordCallbackCancellation(requestOutcome, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.recordRequestInterruption(
                    requestOutcome, "Interrupted while writing protocol request", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            ProtocolSessionState.TerminalSnapshot outcome = state.terminal();
            if (outcome instanceof ProtocolSessionState.FatalSnapshot fatal) {
                if (fatal.error() != cause) {
                    outputPumps.retainFailure(cause);
                }
                throw fatal.error();
            }
            if (cause instanceof Error error) {
                throw error;
            }
            requestOutcome.throwIfFailed();
            if (cause instanceof ProtocolSessionException protocolException) {
                throw protocolException;
            }
            if (cause instanceof IOException ioException) {
                throw state.failure(
                        ProtocolSessionException.Reason.BROKEN_PIPE, "Could not write protocol request", ioException);
            }
            if (cause instanceof ProcessExitedException processExited) {
                throw state.failure(
                        ProtocolSessionException.Reason.PROCESS_EXITED,
                        "Protocol process exited before the request could be written",
                        processExited);
            }
            throw state.failure(ProtocolSessionException.Reason.FAILURE, "Could not write protocol request", cause);
        } finally {
            capabilityScope.invalidate();
        }
    }

    private O readResponse(long deadlineNanos, ProtocolSessionState.RequestOutcome requestOutcome) {
        ProtocolRuntimeFailures trackedFailures = state.trackedFailures(requestOutcome);
        ProtocolResponseBudget budget =
                new ProtocolResponseBudget(options.maxResponseBytes(), options.maxResponseChars(), trackedFailures);
        RequestCapabilityScope capabilityScope = new RequestCapabilityScope("ProtocolReader");
        ProtocolReaders readers = output.readers(deadlineNanos, budget, trackedFailures, capabilityScope);
        try {
            return callbackRunner.run(
                    "procwright-protocol-decoder-",
                    deadlineNanos,
                    callbackCancellation,
                    this::failLateCallbackFailure,
                    failure -> {
                        capabilityScope.invalidate();
                        state.selectCallbackAbandonment(
                                requestOutcome, "Interrupted while decoding protocol response", failure);
                    },
                    () -> {
                        capabilityScope.activate();
                        try {
                            return Objects.requireNonNull(
                                    adapter.readResponse(readers), "Protocol response decoder returned null");
                        } finally {
                            capabilityScope.invalidate();
                        }
                    });
        } catch (TimeoutException exception) {
            throw state.recordRequestTimeout(requestOutcome);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw recordCallbackCancellation(requestOutcome, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.recordRequestInterruption(
                    requestOutcome, "Interrupted while decoding protocol response", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            ProtocolSessionState.TerminalSnapshot outcome = state.terminal();
            if (outcome instanceof ProtocolSessionState.FatalSnapshot fatal) {
                if (fatal.error() != cause) {
                    outputPumps.retainFailure(cause);
                }
                throw fatal.error();
            }
            if (cause instanceof Error error) {
                throw error;
            }
            requestOutcome.throwIfFailed();
            if (cause instanceof ProtocolSessionException protocolException) {
                throw protocolException;
            }
            throw state.failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, "Protocol response decoder failed", cause);
        } finally {
            capabilityScope.invalidate();
        }
    }

    private ProtocolSessionException recordCallbackCancellation(
            ProtocolSessionState.RequestOutcome requestOutcome, BoundedTaskRunner.TaskCancelledException cancellation) {
        ProtocolSessionException failure = state.selectCallbackCancellation(requestOutcome, cancellation);
        if (!state.isClosed()) {
            closePreserving(failure);
        }
        return failure;
    }

    private ProtocolTranscript protocolTranscript() {
        return output.transcript();
    }

    private OptionalInt exitCodeSnapshot() {
        return session.processExitCode();
    }

    private void closePreserving(Throwable failure) {
        try {
            closeWithEvent(true, failure);
        } catch (Throwable closeFailure) {
            outputPumps.retainFailure(closeFailure);
        }
    }

    private void closeTerminalPreserving(Throwable failure) {
        try {
            closeWithEvent(false, failure);
        } catch (Throwable closeFailure) {
            outputPumps.retainFailure(closeFailure);
        }
    }

    private void closeQuietly(Throwable candidate) {
        ProtocolSessionState.TerminalSnapshot outcome = state.terminal();
        Throwable primary = terminalPrimaryOr(outcome, candidate);
        try {
            closeWithEvent(false, primary);
        } catch (RuntimeException ignored) {
            // The reader observes the original protocol failure.
        }
    }

    private void failFatalOutput(Error error) {
        output.failFatal(error);
    }

    private void failLateCallbackFailure(Thread sourceThread, Throwable failure) {
        if (failure instanceof Error error) {
            failFatalOutput(error);
            return;
        }
        BoundedTaskRunner.reportLateFailure(sourceThread, failure);
    }

    private static Throwable terminalPrimaryOr(ProtocolSessionState.TerminalSnapshot outcome, Throwable fallback) {
        if (outcome instanceof ProtocolSessionState.FailureSnapshot failure) {
            return failure.primary();
        }
        if (outcome instanceof ProtocolSessionState.FatalSnapshot fatal) {
            return fatal.error();
        }
        return fallback;
    }

    record Dependencies(
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            LongSupplier outputNanoTime,
            ProtocolCallbackRunner callbackRunner,
            SerializedRequestGate.Waiter requestLockWaiter) {

        Dependencies {
            Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
            Objects.requireNonNull(pumpStarter, "pumpStarter");
            Objects.requireNonNull(outputNanoTime, "outputNanoTime");
            Objects.requireNonNull(callbackRunner, "callbackRunner");
            Objects.requireNonNull(requestLockWaiter, "requestLockWaiter");
        }

        static Dependencies defaults() {
            return new Dependencies(
                    ZeroReadBackoff.exponential(),
                    PumpStarter.threading(),
                    System::nanoTime,
                    ProtocolCallbackRunner.bounded(),
                    SerializedRequestGate.Waiter.timed());
        }
    }

    interface ProtocolCallbackRunner {

        <T> T run(
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.CancellationSignal cancellation,
                BoundedTaskRunner.LateFailureHandler lateFailureHandler,
                BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
                BoundedTaskRunner.Task<T> task)
                throws TimeoutException, InterruptedException, ExecutionException,
                        BoundedTaskRunner.TaskCancelledException;

        static ProtocolCallbackRunner bounded() {
            return BoundedProtocolCallbackRunner.INSTANCE;
        }
    }

    private enum BoundedProtocolCallbackRunner implements ProtocolCallbackRunner {
        INSTANCE;

        @Override
        public <T> T run(
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.CancellationSignal cancellation,
                BoundedTaskRunner.LateFailureHandler lateFailureHandler,
                BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
                BoundedTaskRunner.Task<T> task)
                throws TimeoutException, InterruptedException, ExecutionException,
                        BoundedTaskRunner.TaskCancelledException {
            return BoundedTaskRunner.runReportingLateFailure(
                    BoundedTaskLimits.PROTOCOL_CALLBACKS,
                    threadPrefix,
                    deadlineNanos,
                    cancellation,
                    lateFailureHandler,
                    abandonmentHandler,
                    task);
        }
    }
}
