/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderMalfunctionError;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
    private static final int ZERO_READ_BACKOFF_STEPS = 8;
    private final DefaultSession session;
    private final ProtocolAdapter<I, O> adapter;
    private final ProtocolSessionSettings options;
    private final ZeroReadBackoff zeroReadBackoff;
    private final OutputPumpCoordinator outputPumps;
    private final ProtocolTranscriptBuffer transcript;
    private final ProtocolOutputQueue stdout;
    private final ProtocolOutputQueue stderr;
    private final ProtocolTextDecoderState stdoutTextDecoder;
    private final ProtocolTextDecoderState stderrTextDecoder;
    private final TransitionProbe transitionProbe;
    private final ProtocolCallbackRunner callbackRunner;
    private final RequestLockWaiter requestLockWaiter;
    private final BoundedLifecyclePublisher.Permit exitPublication;
    private final CompletableFuture<SessionExit> exit = new CompletableFuture<>();
    private final AtomicReference<OptionalInt> cachedProcessExitCode = new AtomicReference<>(OptionalInt.empty());
    private final BoundedTaskRunner.CancellationSignal callbackCancellation =
            new BoundedTaskRunner.CancellationSignal();
    private final ReentrantLock requestLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<TerminalOutcome> terminalOutcome = new AtomicReference<>();
    private final Object requestOutcomeLock = new Object();
    private RequestOutcome activeRequestOutcome;
    private final ProtocolRuntimeFailures failures = new ProtocolRuntimeFailures() {
        @Override
        public ProtocolSessionException timeout(Throwable cause) {
            return DefaultProtocolSession.this.timeout(cause);
        }

        @Override
        public ProtocolSessionException interrupted(String message, InterruptedException cause) {
            return DefaultProtocolSession.this.failure(ProtocolSessionException.Reason.FAILURE, message, cause);
        }

        @Override
        public ProtocolSessionException closed(Throwable cause) {
            return DefaultProtocolSession.this.closed(cause);
        }

        @Override
        public ProtocolSessionException eof() {
            OptionalInt exitCode = cachedProcessExitCode.get();
            return exitCode.isPresent()
                    ? DefaultProtocolSession.this.processExited(exitCode)
                    : DefaultProtocolSession.this.eof();
        }

        @Override
        public ProtocolSessionException processExited(OptionalInt exitCode) {
            return DefaultProtocolSession.this.processExited(exitCode);
        }

        @Override
        public ProtocolSessionException failure(
                ProtocolSessionException.Reason reason, String message, Throwable cause) {
            return DefaultProtocolSession.this.failure(reason, message, cause);
        }
    };

    public DefaultProtocolSession(
            DefaultSession session, ProtocolAdapter<I, O> adapter, ProtocolSessionSettings options) {
        this(session, adapter, options, ZeroReadBackoff.exponential(), PumpStarter.threading(), TransitionProbe.none());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff) {
        this(session, adapter, options, zeroReadBackoff, PumpStarter.threading(), TransitionProbe.none());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter) {
        this(session, adapter, options, zeroReadBackoff, pumpStarter, TransitionProbe.none());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            TransitionProbe transitionProbe) {
        this(session, adapter, options, zeroReadBackoff, pumpStarter, transitionProbe, System::nanoTime);
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            TransitionProbe transitionProbe,
            LongSupplier outputNanoTime) {
        this(
                session,
                adapter,
                options,
                zeroReadBackoff,
                pumpStarter,
                transitionProbe,
                outputNanoTime,
                ProtocolCallbackRunner.bounded());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            TransitionProbe transitionProbe,
            LongSupplier outputNanoTime,
            ProtocolCallbackRunner callbackRunner) {
        this(
                session,
                adapter,
                options,
                zeroReadBackoff,
                pumpStarter,
                transitionProbe,
                outputNanoTime,
                callbackRunner,
                RequestLockWaiter.timed());
    }

    DefaultProtocolSession(
            DefaultSession session,
            ProtocolAdapter<I, O> adapter,
            ProtocolSessionSettings options,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            TransitionProbe transitionProbe,
            LongSupplier outputNanoTime,
            ProtocolCallbackRunner callbackRunner,
            RequestLockWaiter requestLockWaiter) {
        this.session = Objects.requireNonNull(session, "session");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.options = Objects.requireNonNull(options, "options");
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.transitionProbe = Objects.requireNonNull(transitionProbe, "transitionProbe");
        this.callbackRunner = Objects.requireNonNull(callbackRunner, "callbackRunner");
        this.requestLockWaiter = Objects.requireNonNull(requestLockWaiter, "requestLockWaiter");
        this.outputPumps = new OutputPumpCoordinator(session, OUTPUT_OWNER);
        int responsePendingByteLimit = ProtocolResponseReader.pendingByteLimit(options);
        int responseOutputWithoutInputLimit = ProtocolResponseReader.outputWithoutInputLimit(options);
        int decodedLineSuffixLimit = ProtocolResponseReader.decodedLineSuffixLimit(options);
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
        this.transcript = initializedTranscript;
        this.stdoutTextDecoder = stdoutDecoder;
        this.stderrTextDecoder = stderrDecoder;
        LongSupplier checkedOutputNanoTime = Objects.requireNonNull(outputNanoTime, "outputNanoTime");
        this.stdout = new ProtocolOutputQueue(
                options.outputBacklogLimit(),
                ProtocolOutputQueue.OverflowPolicy.STRICT,
                checkedOutputNanoTime,
                transitionProbe::beforeOutputWait,
                transitionProbe::beforeOutputTimeoutFailure,
                cachedProcessExitCode::get,
                null);
        this.stderr = new ProtocolOutputQueue(
                options.outputBacklogLimit(),
                ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ,
                checkedOutputNanoTime,
                transitionProbe::beforeOutputWait,
                transitionProbe::beforeOutputTimeoutFailure,
                cachedProcessExitCode::get,
                null);
        BoundedLifecyclePublisher.Reservation publicationReservation =
                BoundedLifecyclePublisher.shared().reserve(1);
        this.exitPublication = publicationReservation.takePermit();
        DefaultSession.ProcessExitObservation.Registration observationRegistration = null;
        try {
            observationRegistration = this.session.processExitObservation().subscribe(cachedProcessExitCode);
            startPumps(Objects.requireNonNull(pumpStarter, "pumpStarter"));
            observeExitAfterOutputCleanup();
        } catch (RuntimeException | Error failure) {
            if (observationRegistration != null) {
                observationRegistration.close();
            }
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
        Duration requestTimeout = requirePositive(timeout, "timeout");
        long deadlineNanos = DurationSupport.deadlineFromNow(requestTimeout);
        acquireRequestLock(deadlineNanos);
        RequestOutcome requestOutcome = beginRequest();
        try {
            ensureOpen();
            writeRequest(request, deadlineNanos, requestOutcome);
            requestOutcome.throwIfFailed();
            O response = readResponse(deadlineNanos, requestOutcome);
            recordDeadlineFailure(deadlineNanos, requestOutcome);
            completeRequest(requestOutcome);
            return response;
        } catch (ProtocolSessionException exception) {
            ProtocolSessionException primary = primaryFailure(requestOutcome, exception);
            TerminalOutcome outcome = terminalOutcome.get();
            if (primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                outcome = recordTerminalFailure(primary.reason(), primary.getMessage(), primary);
            }
            Error fatalError = fatalError(outcome);
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, primary);
                closePreserving(fatalError);
            } else if (primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                closePreserving(primary);
            }
            throw finalizeProtocolFailure(requestOutcome, primary);
        } catch (Error error) {
            Error fatalError = Objects.requireNonNull(fatalError(recordFatalError(error)), "fatalError");
            closePreserving(fatalError);
            throw finalizeFatalFailure(requestOutcome, fatalError);
        } finally {
            endRequest(requestOutcome);
            requestLock.unlock();
        }
    }

    private RequestOutcome beginRequest() {
        synchronized (requestOutcomeLock) {
            if (activeRequestOutcome != null) {
                throw new IllegalStateException("protocol request outcome is already active");
            }
            activeRequestOutcome = new RequestOutcome();
            return activeRequestOutcome;
        }
    }

    private void completeRequest(RequestOutcome requestOutcome) {
        ProtocolSessionException requestFailure;
        TerminalOutcome sessionOutcome;
        synchronized (requestOutcomeLock) {
            requestFailure = requestOutcome.failure();
            sessionOutcome = terminalOutcome.get();
            if (requestFailure == null && sessionOutcome == null) {
                activeRequestOutcome = null;
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

    private void endRequest(RequestOutcome requestOutcome) {
        synchronized (requestOutcomeLock) {
            if (activeRequestOutcome == requestOutcome) {
                activeRequestOutcome = null;
            }
        }
    }

    private ProtocolSessionException finalizeProtocolFailure(
            RequestOutcome requestOutcome, ProtocolSessionException fallback) {
        transitionProbe.beforeRequestFailureReturn();
        synchronized (requestOutcomeLock) {
            if (activeRequestOutcome == requestOutcome) {
                activeRequestOutcome = null;
            }
            Error fatalError = fatalError(terminalOutcome.get());
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, fallback);
                throw fatalError;
            }
            return fallback;
        }
    }

    private Error finalizeFatalFailure(RequestOutcome requestOutcome, Error fallback) {
        transitionProbe.beforeRequestFailureReturn();
        synchronized (requestOutcomeLock) {
            if (activeRequestOutcome == requestOutcome) {
                activeRequestOutcome = null;
            }
            Error fatalError = fatalError(terminalOutcome.get());
            if (fatalError == null) {
                return fallback;
            }
            SuppressionSupport.attach(fatalError, fallback);
            return fatalError;
        }
    }

    private void acquireRequestLock(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw arbitrateRequestLockFailure(() -> timeout(null));
        }
        try {
            if (!requestLockWaiter.acquire(requestLock, remainingNanos)) {
                throw arbitrateRequestLockFailure(() -> timeout(null));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw arbitrateRequestLockFailure(() -> failure(
                    ProtocolSessionException.Reason.FAILURE,
                    "Interrupted while waiting to start protocol request",
                    exception));
        }
    }

    private ProtocolSessionException arbitrateRequestLockFailure(Supplier<ProtocolSessionException> localFailure) {
        TerminalOutcome outcome;
        synchronized (requestOutcomeLock) {
            outcome = terminalOutcome.get();
        }
        if (outcome != null) {
            throwTerminalOutcome(outcome);
        }
        return Objects.requireNonNull(localFailure.get(), "localFailure");
    }

    private void recordDeadlineFailure(long deadlineNanos, RequestOutcome requestOutcome) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            recordRequestTimeout(requestOutcome);
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
        session.observeExit((result, failure) -> {
            outputPumps.publishAfterOutputCleanup(() -> exitPublication.publish(() -> {
                session.awaitPhysicalOutputPublication();
                if (failure == null) {
                    exit.complete(result);
                } else {
                    exit.completeExceptionally(failure);
                }
            }));
        });
    }

    @Override
    public void close() {
        closeWithEvent(true);
    }

    private void closeWithEvent(boolean publishClosed) {
        closeWithEvent(publishClosed, null);
    }

    private void closeWithEvent(boolean publishClosed, Throwable primary) {
        TerminalOutcome outcome;
        boolean lifecycleOwner;
        synchronized (requestOutcomeLock) {
            lifecycleOwner = !closed.getAndSet(true);
            outcome = terminalOutcome.get();
            if (lifecycleOwner && publishClosed && outcome == null) {
                outcome = ClosedTerminal.INSTANCE;
                terminalOutcome.set(outcome);
            }
        }
        if (lifecycleOwner) {
            callbackCancellation.cancel();
        }
        try {
            if (lifecycleOwner && publishClosed) {
                publishTerminalWake(outcome);
            }
        } finally {
            if (primary != null) {
                outputPumps.closeSessionPreserving(primary);
            } else if (lifecycleOwner) {
                outputPumps.closeSession();
            }
        }
    }

    private void publishTerminalWake(TerminalOutcome outcome) {
        if (outcome instanceof TerminalFailure failure) {
            stdout.failAndClear(failure.reason(), failure.cause());
            stderr.failAndClear(failure.reason(), failure.cause());
        } else if (outcome instanceof FatalTerminalFailure fatalFailure) {
            publishFatalWake(fatalFailure.error());
        } else {
            stdout.close();
            stderr.close();
        }
    }

    private void startPumps(PumpStarter pumpStarter) {
        outputPumps.start(
                pumpStarter,
                "procwright-protocol-stdout-",
                stream -> runPump("stdout", stream, stdout),
                "procwright-protocol-stderr-",
                stream -> runPump("stderr", stream, stderr),
                () -> closed.set(true));
    }

    private void runPump(String streamName, InputStream stream, ProtocolOutputQueue output) {
        try {
            pump(streamName, stream, output);
        } catch (RuntimeException failure) {
            failRuntimeOutput(failure);
        } catch (Error error) {
            failFatalOutput(error);
        }
    }

    private void pump(String streamName, InputStream stream, ProtocolOutputQueue output) {
        byte[] buffer = new byte[8192];
        try (stream) {
            int consecutiveZeroReads = 0;
            while (!closed.get()) {
                int count = stream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    consecutiveZeroReads = Math.min(consecutiveZeroReads + 1, ZERO_READ_BACKOFF_STEPS);
                    if (!zeroReadBackoff.pause(consecutiveZeroReads, closed::get)) {
                        return;
                    }
                    continue;
                }
                consecutiveZeroReads = 0;
                transcript.appendStream(streamName, buffer, count);
                if (!output.offer(Arrays.copyOf(buffer, count))) {
                    Throwable primary = failOutputBacklogOverflow();
                    closeQuietly(primary);
                    return;
                }
            }
            if (closed.get()) {
                return;
            }
            transcript.endStream(streamName);
        } catch (ProtocolTranscriptBuffer.TranscriptDecodingException exception) {
            Throwable primary = failTranscriptDecoding(exception);
            closeQuietly(primary);
            return;
        } catch (IOException exception) {
            if (!endTranscript(streamName)) {
                return;
            }
            if (!closed.get()) {
                output.failure(reasonFor(exception), exception);
            }
            return;
        }
        output.eof(cachedProcessExitCode.get());
    }

    private boolean endTranscript(String streamName) {
        try {
            transcript.endStream(streamName);
            return true;
        } catch (ProtocolTranscriptBuffer.TranscriptDecodingException exception) {
            if (!closed.get()) {
                Throwable primary = failTranscriptDecoding(exception);
                closeQuietly(primary);
            }
            return false;
        }
    }

    private void writeRequest(I request, long deadlineNanos, RequestOutcome requestOutcome) {
        ProtocolRuntimeFailures trackedFailures = trackedFailures(requestOutcome);
        RequestCapabilityScope capabilityScope = new RequestCapabilityScope("ProtocolWriter");
        ProtocolRequestWriter writer =
                new ProtocolRequestWriter(session, options, deadlineNanos, trackedFailures, capabilityScope);
        try {
            callbackRunner.run(
                    "procwright-protocol-stdin-",
                    deadlineNanos,
                    callbackCancellation,
                    (thread, failure) -> failLateCallbackFailure(requestOutcome, thread, failure),
                    failure -> {
                        capabilityScope.invalidate();
                        selectCallbackAbandonment(
                                requestOutcome, "Interrupted while writing protocol request", failure);
                    },
                    () -> {
                        transitionProbe.beforeWriteAdmission();
                        // This shared-lock state check is the write-callback admission point.
                        ensureOpen();
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
            throw recordRequestTimeout(requestOutcome);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw recordCallbackCancellation(requestOutcome, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw recordRequestInterruption(requestOutcome, "Interrupted while writing protocol request", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            Error fatalError = fatalError(terminalOutcome.get());
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, cause);
                throw fatalError;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            requestOutcome.throwIfFailed();
            if (cause instanceof ProtocolSessionException protocolException) {
                throw protocolException;
            }
            if (cause instanceof IOException ioException) {
                throw failure(
                        ProtocolSessionException.Reason.BROKEN_PIPE, "Could not write protocol request", ioException);
            }
            if (cause instanceof ProcessExitedException processExited) {
                throw failure(
                        ProtocolSessionException.Reason.PROCESS_EXITED,
                        "Protocol process exited before the request could be written",
                        processExited);
            }
            throw failure(ProtocolSessionException.Reason.FAILURE, "Could not write protocol request", cause);
        } finally {
            capabilityScope.invalidate();
        }
    }

    private O readResponse(long deadlineNanos, RequestOutcome requestOutcome) {
        ProtocolRuntimeFailures trackedFailures = trackedFailures(requestOutcome);
        ProtocolResponseBudget budget =
                new ProtocolResponseBudget(options.maxResponseBytes(), options.maxResponseChars(), trackedFailures);
        RequestCapabilityScope capabilityScope = new RequestCapabilityScope("ProtocolReader");
        Readers readers = new Readers(
                new ProtocolResponseReader(
                        stdout, options, deadlineNanos, budget, stdoutTextDecoder, trackedFailures, capabilityScope),
                new ProtocolResponseReader(
                        stderr, options, deadlineNanos, budget, stderrTextDecoder, trackedFailures, capabilityScope));
        try {
            return callbackRunner.run(
                    "procwright-protocol-decoder-",
                    deadlineNanos,
                    callbackCancellation,
                    (thread, failure) -> failLateCallbackFailure(requestOutcome, thread, failure),
                    failure -> {
                        capabilityScope.invalidate();
                        selectCallbackAbandonment(
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
            throw recordRequestTimeout(requestOutcome);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw recordCallbackCancellation(requestOutcome, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw recordRequestInterruption(requestOutcome, "Interrupted while decoding protocol response", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            Error fatalError = fatalError(terminalOutcome.get());
            if (fatalError != null) {
                SuppressionSupport.attach(fatalError, cause);
                throw fatalError;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            requestOutcome.throwIfFailed();
            if (cause instanceof ProtocolSessionException protocolException) {
                throw protocolException;
            }
            throw failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, "Protocol response decoder failed", cause);
        } finally {
            capabilityScope.invalidate();
        }
    }

    private void ensureOpen() {
        TerminalOutcome outcome;
        boolean unavailable;
        synchronized (requestOutcomeLock) {
            outcome = terminalOutcome.get();
            unavailable = closed.get();
        }
        if (outcome != null) {
            throwTerminalOutcome(outcome);
        }
        if (unavailable) {
            throw closed(null);
        }
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
                protocolTranscript(),
                exitCodeSnapshot(),
                "Protocol session was closed by an earlier failure: " + failure.message(),
                failure.cause());
    }

    private TerminalOutcome recordTerminalFailure(
            ProtocolSessionException.Reason reason, String message, Throwable cause) {
        boolean selected = false;
        TerminalOutcome outcome;
        synchronized (requestOutcomeLock) {
            outcome = terminalOutcome.get();
            if (outcome == null) {
                outcome = new TerminalFailure(reason, message, cause);
                terminalOutcome.set(outcome);
                selected = true;
            } else if (!(outcome instanceof ClosedTerminal)) {
                SuppressionSupport.attach(terminalPrimary(outcome), cause);
            }
            if (activeRequestOutcome != null && outcome instanceof TerminalFailure failure) {
                selectActiveTerminalFailure(activeRequestOutcome, terminalException(failure));
            }
        }
        if (selected) {
            transitionProbe.afterTerminalSelection();
        }
        return outcome;
    }

    private TerminalOutcome recordFatalError(Error error) {
        boolean selected = false;
        boolean promoted = false;
        TerminalOutcome outcome;
        synchronized (requestOutcomeLock) {
            outcome = terminalOutcome.get();
            if (outcome == null) {
                outcome = new FatalTerminalFailure(error);
                terminalOutcome.set(outcome);
                selected = true;
            } else if (outcome instanceof FatalTerminalFailure fatalFailure) {
                SuppressionSupport.attach(fatalFailure.error(), error);
            } else {
                if (outcome instanceof TerminalFailure failure) {
                    SuppressionSupport.attach(error, failure.cause());
                }
                ProtocolSessionException activeFailure =
                        activeRequestOutcome == null ? null : activeRequestOutcome.failure();
                SuppressionSupport.attach(error, activeFailure);
                outcome = new FatalTerminalFailure(error);
                terminalOutcome.set(outcome);
                promoted = true;
            }
        }
        if (selected) {
            transitionProbe.afterTerminalSelection();
        } else if (promoted) {
            transitionProbe.afterFatalPromotion();
        }
        return outcome;
    }

    private ProtocolSessionException recordRequestFailure(
            RequestOutcome requestOutcome, Supplier<ProtocolSessionException> failureFactory) {
        ProtocolSessionException candidate = Objects.requireNonNull(failureFactory.get(), "failure");
        ProtocolSessionException selectedFailure;
        boolean selectedTerminal = false;
        synchronized (requestOutcomeLock) {
            TerminalOutcome outcome = terminalOutcome.get();
            if (outcome == null) {
                if (candidate.reason() == ProtocolSessionException.Reason.CLOSED) {
                    selectedFailure = requestOutcome.record(candidate);
                } else {
                    ProtocolSessionException primary = requestOutcome.failure();
                    if (primary == null) {
                        primary = requestOutcome.record(candidate);
                    } else if (primary.reason() == ProtocolSessionException.Reason.CLOSED) {
                        primary = requestOutcome.replaceWithTerminal(candidate);
                    }
                    terminalOutcome.set(new TerminalFailure(primary.reason(), primary.getMessage(), primary));
                    selectedFailure = primary;
                    selectedTerminal = true;
                }
            } else if (outcome instanceof TerminalFailure failure) {
                ProtocolSessionException primary = requestOutcome.failure();
                if (primary == null) {
                    primary = requestOutcome.record(terminalException(failure));
                } else if (primary.reason() == ProtocolSessionException.Reason.CLOSED) {
                    primary = requestOutcome.replaceWithTerminal(terminalException(failure));
                }
                SuppressionSupport.attach(failure.cause(), candidate);
                selectedFailure = primary;
            } else if (outcome instanceof ClosedTerminal) {
                ProtocolSessionException primary = requestOutcome.failure();
                if (primary == null || primary.reason() != ProtocolSessionException.Reason.CLOSED) {
                    ProtocolSessionException closedFailure =
                            candidate.reason() == ProtocolSessionException.Reason.CLOSED ? candidate : closed(null);
                    primary = requestOutcome.replaceWithTerminal(closedFailure);
                } else {
                    SuppressionSupport.attach(primary, candidate);
                }
                selectedFailure = primary;
            } else {
                FatalTerminalFailure fatalFailure = (FatalTerminalFailure) outcome;
                SuppressionSupport.attach(fatalFailure.error(), candidate);
                selectedFailure = candidate;
            }
        }
        if (selectedTerminal) {
            transitionProbe.afterTerminalSelection();
        }
        return selectedFailure;
    }

    private void selectActiveTerminalFailure(RequestOutcome requestOutcome, ProtocolSessionException terminalFailure) {
        ProtocolSessionException current = requestOutcome.failure();
        if (current == null) {
            requestOutcome.record(terminalFailure);
        } else if (current.reason() == ProtocolSessionException.Reason.CLOSED) {
            requestOutcome.replaceWithTerminal(terminalFailure);
        }
    }

    private ProtocolRuntimeFailures trackedFailures(RequestOutcome requestOutcome) {
        return new ProtocolRuntimeFailures() {
            @Override
            public ProtocolSessionException timeout(Throwable cause) {
                return recordRequestTimeout(requestOutcome);
            }

            @Override
            public ProtocolSessionException interrupted(String message, InterruptedException cause) {
                return recordRequestInterruption(requestOutcome, message, cause);
            }

            @Override
            public ProtocolSessionException closed(Throwable cause) {
                return recordRequestFailure(requestOutcome, () -> failures.closed(cause));
            }

            @Override
            public ProtocolSessionException eof() {
                return recordRequestFailure(requestOutcome, failures::eof);
            }

            @Override
            public ProtocolSessionException processExited(OptionalInt exitCode) {
                return recordRequestFailure(requestOutcome, () -> failures.processExited(exitCode));
            }

            @Override
            public ProtocolSessionException failure(
                    ProtocolSessionException.Reason reason, String message, Throwable cause) {
                return recordRequestFailure(requestOutcome, () -> failures.failure(reason, message, cause));
            }
        };
    }

    private ProtocolSessionException primaryFailure(RequestOutcome requestOutcome, ProtocolSessionException fallback) {
        ProtocolSessionException primary = requestOutcome.failure();
        return primary == null ? fallback : primary;
    }

    private ProtocolSessionException recordRequestTimeout(RequestOutcome requestOutcome) {
        return recordRequestFailure(requestOutcome, requestOutcome::timeoutFailure);
    }

    private ProtocolSessionException recordRequestInterruption(
            RequestOutcome requestOutcome, String message, InterruptedException cause) {
        return recordRequestFailure(requestOutcome, () -> requestOutcome.interruptionFailure(message, cause));
    }

    private ProtocolSessionException recordCallbackCancellation(
            RequestOutcome requestOutcome, BoundedTaskRunner.TaskCancelledException cancellation) {
        ProtocolSessionException failure = selectCallbackCancellation(requestOutcome, cancellation);
        if (!closed.get()) {
            closePreserving(failure);
        }
        return failure;
    }

    private ProtocolSessionException selectCallbackCancellation(
            RequestOutcome requestOutcome, BoundedTaskRunner.TaskCancelledException cancellation) {
        return recordRequestFailure(requestOutcome, () -> requestOutcome.cancellationFailure(cancellation));
    }

    private void selectCallbackAbandonment(RequestOutcome requestOutcome, String interruptionMessage, Throwable cause) {
        if (cause instanceof TimeoutException) {
            recordRequestTimeout(requestOutcome);
        } else if (cause instanceof InterruptedException interruption) {
            recordRequestInterruption(requestOutcome, interruptionMessage, interruption);
        } else if (cause instanceof BoundedTaskRunner.TaskCancelledException cancellation) {
            selectCallbackCancellation(requestOutcome, cancellation);
        } else {
            throw new IllegalArgumentException("Unsupported callback abandonment", cause);
        }
    }

    private ProtocolSessionException timeout(Throwable cause) {
        return new ProtocolSessionException(
                ProtocolSessionException.Reason.TIMEOUT, protocolTranscript(), "Protocol request timed out", cause);
    }

    private ProtocolSessionException closed(Throwable cause) {
        return cause == null
                ? new ProtocolSessionException(
                        ProtocolSessionException.Reason.CLOSED, protocolTranscript(), "Protocol session is closed")
                : new ProtocolSessionException(
                        ProtocolSessionException.Reason.CLOSED,
                        protocolTranscript(),
                        "Protocol session is closed",
                        cause);
    }

    private ProtocolSessionException eof() {
        return new ProtocolSessionException(
                ProtocolSessionException.Reason.EOF,
                protocolTranscript(),
                OptionalInt.empty(),
                "Protocol session reached EOF",
                null);
    }

    private ProtocolSessionException processExited(OptionalInt exitCode) {
        return new ProtocolSessionException(
                ProtocolSessionException.Reason.PROCESS_EXITED,
                protocolTranscript(),
                exitCode,
                "Protocol process exited before a complete response was read",
                null);
    }

    private ProtocolSessionException failure(ProtocolSessionException.Reason reason, String message, Throwable cause) {
        return new ProtocolSessionException(reason, protocolTranscript(), exitCodeSnapshot(), message, cause);
    }

    private ProtocolTranscript protocolTranscript() {
        return transcript.snapshot();
    }

    private OptionalInt exitCodeSnapshot() {
        OptionalInt observed = cachedProcessExitCode.get();
        if (observed.isPresent()) {
            return observed;
        }
        CompletableFuture<SessionExit> exit = session.onExit();
        if (!exit.isDone()) {
            return OptionalInt.empty();
        }
        try {
            return exit.getNow(new SessionExit(OptionalInt.empty(), false)).exitCode();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    private void closePreserving(Throwable failure) {
        try {
            closeWithEvent(true, failure);
        } catch (Throwable closeFailure) {
            SuppressionSupport.attach(failure, closeFailure);
        }
    }

    private void closeTerminalPreserving(Throwable failure) {
        try {
            closeWithEvent(false, failure);
        } catch (Throwable closeFailure) {
            SuppressionSupport.attach(failure, closeFailure);
        }
    }

    private void closeQuietly(Throwable candidate) {
        TerminalOutcome outcome = terminalOutcome.get();
        Throwable primary = outcome == null || outcome instanceof ClosedTerminal ? candidate : terminalPrimary(outcome);
        try {
            closeWithEvent(false, primary);
        } catch (RuntimeException ignored) {
            // The reader observes the original protocol failure.
        }
    }

    private void failFatalOutput(Error error) {
        TerminalOutcome outcome = recordFatalError(error);
        Throwable primary = terminalPrimary(outcome);
        if (outcome instanceof FatalTerminalFailure fatalFailure) {
            publishFatalWake(fatalFailure.error());
        }
        closeTerminalPreserving(primary);
    }

    private void failLateCallbackFailure(RequestOutcome requestOutcome, Thread sourceThread, Throwable failure) {
        try {
            if (failure instanceof Error error) {
                failFatalOutput(error);
                return;
            }
            synchronized (requestOutcomeLock) {
                TerminalOutcome outcome = terminalOutcome.get();
                if (outcome instanceof FatalTerminalFailure fatalFailure) {
                    SuppressionSupport.attach(fatalFailure.error(), failure);
                    return;
                }
                ProtocolSessionException requestFailure = requestOutcome.failure();
                if (requestFailure != null) {
                    SuppressionSupport.attach(requestFailure, failure);
                } else if (outcome instanceof TerminalFailure terminalFailure) {
                    SuppressionSupport.attach(terminalFailure.cause(), failure);
                }
            }
        } finally {
            try {
                transitionProbe.afterLateCallbackFailure();
            } finally {
                BoundedTaskRunner.reportLateFailure(sourceThread, failure);
            }
        }
    }

    private void failRuntimeOutput(RuntimeException failure) {
        boolean publishFailure = !closed.get();
        TerminalOutcome outcome = recordTerminalFailure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not read protocol output", failure);
        Throwable primary = outcome instanceof ClosedTerminal ? failure : terminalPrimary(outcome);
        try {
            if (publishFailure && outcome instanceof TerminalFailure terminalFailure) {
                stdout.failAndClear(terminalFailure.reason(), terminalFailure.cause());
                stderr.failAndClear(terminalFailure.reason(), terminalFailure.cause());
            }
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(primary, publicationFailure);
        } finally {
            closeTerminalPreserving(primary);
        }
    }

    private void publishFatalWake(Error error) {
        try {
            stdout.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, error);
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(error, publicationFailure);
        }
        try {
            stderr.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, error);
        } catch (Throwable publicationFailure) {
            SuppressionSupport.attach(error, publicationFailure);
        }
    }

    private Throwable terminalPrimary(TerminalOutcome outcome) {
        if (outcome instanceof FatalTerminalFailure fatalFailure) {
            return fatalFailure.error();
        }
        if (outcome instanceof TerminalFailure failure) {
            return failure.cause();
        }
        throw new IllegalArgumentException("closed terminal has no failure cause");
    }

    private static Error fatalError(TerminalOutcome outcome) {
        return outcome instanceof FatalTerminalFailure failure ? failure.error() : null;
    }

    private Throwable failOutputBacklogOverflow() {
        // Only strict stdout returns false. Stderr retains a fail-on-read marker without
        // terminating a stdout-only session.
        CommandExecutionException failure = new CommandExecutionException("Protocol stdout backlog overflow");
        TerminalOutcome outcome = recordTerminalFailure(
                ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "Protocol output backlog overflow", failure);
        stdout.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure);
        stderr.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure);
        return outcome instanceof ClosedTerminal ? failure : terminalPrimary(outcome);
    }

    private Throwable failTranscriptDecoding(ProtocolTranscriptBuffer.TranscriptDecodingException failure) {
        TerminalOutcome outcome = recordTerminalFailure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol transcript", failure);
        stdout.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, failure);
        stderr.failAndClear(ProtocolSessionException.Reason.DECODE_ERROR, failure);
        return outcome instanceof ClosedTerminal ? failure : terminalPrimary(outcome);
    }

    private static ProtocolSessionException.Reason reasonFor(IOException exception) {
        return exception instanceof CharacterCodingException
                ? ProtocolSessionException.Reason.DECODE_ERROR
                : ProtocolSessionException.Reason.FAILURE;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private record Readers(ProtocolReader stdout, ProtocolReader stderr) implements ProtocolReaders {}

    interface TransitionProbe {

        void beforeWriteAdmission();

        void afterTerminalSelection();

        default void beforeOutputWait() {}

        default void beforeOutputTimeoutFailure() {}

        default void beforeRequestFailureReturn() {}

        default void afterFatalPromotion() {}

        default void afterLateCallbackFailure() {}

        static TransitionProbe none() {
            return NoOpTransitionProbe.INSTANCE;
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

    interface RequestLockWaiter {

        boolean acquire(ReentrantLock lock, long remainingNanos) throws InterruptedException;

        static RequestLockWaiter timed() {
            return TimedRequestLockWaiter.INSTANCE;
        }
    }

    private enum TimedRequestLockWaiter implements RequestLockWaiter {
        INSTANCE;

        @Override
        public boolean acquire(ReentrantLock lock, long remainingNanos) throws InterruptedException {
            return lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
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
                    BoundedTaskRunner.PROTOCOL_CALLBACKS,
                    threadPrefix,
                    deadlineNanos,
                    cancellation,
                    lateFailureHandler,
                    abandonmentHandler,
                    task);
        }
    }

    private enum NoOpTransitionProbe implements TransitionProbe {
        INSTANCE;

        @Override
        public void beforeWriteAdmission() {}

        @Override
        public void afterTerminalSelection() {}
    }

    private final class RequestOutcome {

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
                    () -> DefaultProtocolSession.this.failure(ProtocolSessionException.Reason.FAILURE, message, cause));
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

        private ProtocolSessionException record(ProtocolSessionException failure) {
            return failures.record(failure);
        }

        private ProtocolSessionException failure() {
            return failures.failure();
        }

        private ProtocolSessionException replaceWithTerminal(ProtocolSessionException failure) {
            return failures.replaceWithTerminal(failure);
        }

        private void throwIfFailed() {
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
