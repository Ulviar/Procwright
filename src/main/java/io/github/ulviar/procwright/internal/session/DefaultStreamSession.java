/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.session.StreamChunk;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.session.StreamSource;
import io.github.ulviar.procwright.session.StreamTranscript;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CoderMalfunctionError;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Handle for a listen-only streaming command.
 *
 * <p>A stream session owns stdout/stderr pumps and dispatches chunks to the configured listener. It does not retain all
 * output; only a bounded diagnostic window is kept for exit and failure signals.
 */
public final class DefaultStreamSession implements StreamSession {

    private static final String OUTPUT_OWNER = "StreamSession";

    private final DefaultSession session;
    private final Duration timeout;
    private final OutputPumpCoordinator outputPumps;
    private final StreamOutputReader outputReader;
    private final StreamListenerDispatcher listenerDispatcher;
    private final DiagnosticEmitter eventDiagnostics;
    private final BoundedTranscriptBuffer diagnostics;
    private final LongSupplier nanoTime;
    private final long startedNanos;
    private final CompletableFuture<StreamExit> exit = new CompletableFuture<>();
    private final BoundedLifecyclePublisher.Permit exitPublication;
    private final StreamSessionState state = new StreamSessionState(2);
    private final StreamTimeoutWatcher timeoutWatcher = new StreamTimeoutWatcher();

    DefaultStreamSession(DefaultSession session, StreamExecutionPlan plan, DiagnosticEmitter eventDiagnostics) {
        this(session, plan, eventDiagnostics, Dependencies.defaults());
    }

    DefaultStreamSession(
            DefaultSession session,
            StreamExecutionPlan plan,
            DiagnosticEmitter eventDiagnostics,
            Dependencies dependencies) {
        this.session = Objects.requireNonNull(session, "session");
        Objects.requireNonNull(plan, "plan");
        Dependencies runtime = Objects.requireNonNull(dependencies, "dependencies");
        this.timeout = plan.timeout();
        this.outputPumps = new OutputPumpCoordinator(session, OUTPUT_OWNER);
        this.outputReader = new StreamOutputReader(
                CharsetPolicy.replace(plan.sessionPlan().charset()), plan.diagnosticLimit(), runtime.zeroReadBackoff());
        this.listenerDispatcher = new StreamListenerDispatcher(plan.listener());
        this.eventDiagnostics = Objects.requireNonNull(eventDiagnostics, "eventDiagnostics");
        this.nanoTime = runtime.nanoTime();
        this.startedNanos = nanoTime.getAsLong();
        this.diagnostics = new BoundedTranscriptBuffer(plan.diagnosticLimit());
        BoundedLifecyclePublisher.Reservation publicationReservation =
                BoundedLifecyclePublisher.shared().reserve(1);
        this.exitPublication = publicationReservation.takePermit();
        boolean pumpsCommitted = false;
        try {
            startPumps(runtime.pumpStarter());
            pumpsCommitted = true;
            startTimeoutWatcher();
            startExitWatcher();
            session.closeStdin();
        } catch (RuntimeException | Error failure) {
            exitPublication.release();
            abortStartup();
            if (pumpsCommitted) {
                outputPumps.closeSessionPreserving(failure);
            }
            throw failure;
        }
    }

    /**
     * Returns a stream-session exit future view. The future completes after the process exits, output pumps drain, and
     * helper-owned physical output cleanup releases its internal ownership.
     *
     * @return stream exit future
     */
    public CompletableFuture<StreamExit> onExit() {
        return exit.copy();
    }

    /**
     * Returns the current bounded diagnostic transcript snapshot.
     *
     * @return diagnostic transcript
     */
    public StreamTranscript diagnostics() {
        return streamTranscript();
    }

    CompletableFuture<Void> timeoutWatcherStopped() {
        return timeoutWatcher.stopped();
    }

    CompletableFuture<Void> physicalOutputCleanup() {
        return session.physicalOutputCleanup();
    }

    /**
     * Stops the underlying process through the configured shutdown policy. Calling this method more than once has no
     * effect.
     */
    @Override
    public void close() {
        if (selectControlOutcome(StreamSessionState.Control.CLOSED)) {
            Throwable failure = null;
            if (!session.terminationPublished()) {
                failure = emitCollecting(
                        DiagnosticEventType.SHUTDOWN_REQUESTED,
                        DiagnosticEmitter.attributes("reason", "close"),
                        failure);
            }
            stopTimeoutWatcher();
            failure = closeOutputPumpsCollecting(failure);
            maybeComplete();
            rethrow(failure);
        }
    }

    private void startPumps(PumpStarter pumpStarter) {
        outputPumps.start(
                pumpStarter,
                "procwright-stream-stdout-",
                stream -> pump(StreamSource.STDOUT, stream),
                "procwright-stream-stderr-",
                stream -> pump(StreamSource.STDERR, stream),
                this::abortStartup);
    }

    private void pump(StreamSource source, InputStream stream) {
        AtomicReference<Throwable> lateFailure = new AtomicReference<>();
        try {
            outputReader.read(
                    stream,
                    state::stopping,
                    (chars, count) -> recordLate(lateFailure, publishDecoded(source, chars, count)));
        } catch (IOException exception) {
            if (!isControlledStop()) {
                recordLate(lateFailure, failOutputRead(exception));
            }
        } catch (RuntimeException | CoderMalfunctionError exception) {
            if (isControlledStop()) {
                recordLate(lateFailure, exception);
            } else {
                recordLate(lateFailure, failOutputRead(exception));
            }
        } catch (Error error) {
            recordLate(lateFailure, failFatal(error));
        } finally {
            if (state.outputPumpCompleted()) {
                listenerDispatcher.stop();
                maybeComplete();
            }
            reportLate(lateFailure.get());
        }
    }

    private Throwable publishDecoded(StreamSource source, char[] chars, int count) {
        if (state.stopping()) {
            return null;
        }
        String text = new String(chars, 0, count);
        boolean truncated = diagnostics.appendStream(source.label(), text);
        if (truncated) {
            eventDiagnostics.emit(
                    DiagnosticEventType.OUTPUT_TRUNCATED,
                    DiagnosticEmitter.attributes(
                            "source", "diagnostics", "limitChars", Integer.toString(diagnostics.limit())));
        }
        return deliver(new StreamChunk(source, text));
    }

    private Throwable failOutputRead(Throwable failure) {
        return recordFailure(StreamException.Reason.OUTPUT_READ_FAILED, "Could not read streaming output", failure);
    }

    private Throwable deliver(StreamChunk chunk) {
        try {
            listenerDispatcher.deliver(chunk, () -> !state.stopping() && !state.hasOutcome());
            return null;
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            if (isControlledStop()) {
                return null;
            }
            return recordFailure(
                    StreamException.Reason.LISTENER_FAILED,
                    "Interrupted while delivering streaming output",
                    interruption);
        } catch (TimeoutException timeoutFailure) {
            return recordFailure(
                    StreamException.Reason.LISTENER_FAILED,
                    "Streaming listener capacity was unavailable",
                    timeoutFailure);
        } catch (ExecutionException listenerFailure) {
            Throwable cause = listenerFailure.getCause();
            if (cause instanceof Error error) {
                return failFatal(error);
            }
            return recordFailure(StreamException.Reason.LISTENER_FAILED, "Streaming listener failed", cause);
        } catch (RuntimeException failure) {
            return recordFailure(
                    StreamException.Reason.LISTENER_FAILED, "Could not invoke streaming listener", failure);
        } catch (Error error) {
            return failFatal(error);
        }
    }

    private void startExitWatcher() {
        session.observeExit((value, throwable) -> {
            if (throwable == null) {
                state.nestedSucceeded(value);
            } else {
                Throwable processFailure = unwrapCompletionFailure(throwable);
                state.nestedFailed(processFailure);
                if (processFailure instanceof Error error) {
                    reportLate(failFatal(error));
                } else {
                    reportLate(recordFailure(
                            StreamException.Reason.PROCESS_FAILED, "Streaming process failed", processFailure));
                }
            }
            maybeComplete();
        });
    }

    private void startTimeoutWatcher() {
        timeoutWatcher.start(timeout, state::hasOutcome, this::expireTimeout);
    }

    void expireTimeout() {
        if (!selectControlOutcome(StreamSessionState.Control.TIMED_OUT)) {
            return;
        }
        Throwable failure = emitCollecting(DiagnosticEventType.TIMEOUT_REACHED, java.util.Map.of(), null);
        failure = emitCollecting(
                DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "timeout"), failure);
        failure = closeOutputPumpsCollecting(failure);
        maybeComplete();
        rethrow(failure);
    }

    private Throwable recordFailure(StreamException.Reason reason, String message, Throwable cause) {
        StreamException exception = new StreamException(reason, message, streamTranscript(), cause);
        StreamSessionState.FailureSelection selection = selectFailure(exception);
        if (selection.installed() && reason == StreamException.Reason.LISTENER_FAILED) {
            emitPreserving(DiagnosticEventType.LISTENER_FAILED, exception);
        }
        activate(selection);
        return selection.reportableFailure();
    }

    private Throwable failFatal(Error error) {
        StreamSessionState.FailureSelection selection = selectFailure(error);
        activate(selection);
        return selection.reportableFailure();
    }

    private StreamSessionState.FailureSelection selectFailure(Throwable candidate) {
        StreamSessionState.FailureSelection selection = state.selectFailure(candidate);
        if (selection.installed()) {
            beginStopping();
        }
        return selection;
    }

    private void activate(StreamSessionState.FailureSelection selection) {
        if (!selection.installed()) {
            maybeComplete();
            return;
        }
        Throwable primary = selection.primary();
        emitPreserving(DiagnosticEventType.PROCESS_FAILED, DiagnosticEmitter.failureAttributes(primary), primary);
        emitPreserving(
                DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "failure"), primary);
        outputPumps.closeSessionPreserving(primary);
        maybeComplete();
    }

    private void maybeComplete() {
        StreamSessionState.Completion completion = state.claimCompletion();
        if (completion == null) {
            return;
        }
        beginStopping();
        if (completion instanceof StreamSessionState.FailedCompletion failed) {
            publishFailure(failed.primary());
            return;
        }
        StreamSessionState.SuccessfulCompletion successful = (StreamSessionState.SuccessfulCompletion) completion;
        stopTimeoutWatcherBeforePublication();
        outputPumps.publishAfterOutputCleanup(
                () -> session.afterPhysicalOutputCleanup(() -> exitPublication.publish(() -> {
                    StreamExit terminal = new StreamExit(
                            successful.exitCode(),
                            successful.timedOut(),
                            successful.closed(),
                            streamTranscript(),
                            DurationSupport.elapsed(startedNanos, nanoTime.getAsLong()));
                    Throwable diagnosticFailure = emitCollecting(
                            DiagnosticEventType.PROCESS_EXITED,
                            exitAttributes(successful.exitCode(), successful.timedOut()),
                            null);
                    exit.complete(terminal);
                    reportLate(diagnosticFailure);
                })));
    }

    private void publishFailure(Throwable primary) {
        stopTimeoutWatcherBeforePublication();
        outputPumps.publishAfterOutputCleanup(() -> session.afterPhysicalOutputCleanup(
                () -> exitPublication.publish(() -> exit.completeExceptionally(primary))));
    }

    private boolean selectControlOutcome(StreamSessionState.Control candidate) {
        boolean selected = state.selectControl(candidate);
        if (selected) {
            beginStopping();
        }
        return selected;
    }

    private boolean isControlledStop() {
        return state.controlledStop();
    }

    private void stopTimeoutWatcher() {
        timeoutWatcher.stop();
    }

    private void stopTimeoutWatcherBeforePublication() {
        timeoutWatcher.stopAndAwait();
    }

    private void abortStartup() {
        beginStopping();
        stopTimeoutWatcher();
    }

    private void emitPreserving(DiagnosticEventType type, Throwable primary) {
        emitPreserving(type, java.util.Map.of(), primary);
    }

    private void emitPreserving(DiagnosticEventType type, java.util.Map<String, String> attributes, Throwable primary) {
        emitCollecting(type, attributes, primary);
    }

    private Throwable emitCollecting(
            DiagnosticEventType type, java.util.Map<String, String> attributes, Throwable primary) {
        try {
            eventDiagnostics.emit(type, attributes);
            return primary;
        } catch (RuntimeException | Error diagnosticFailure) {
            return FailureAggregation.combine(primary, diagnosticFailure, "Multiple stream diagnostic failures");
        }
    }

    private void reportLate(Throwable failure) {
        if (failure != null) {
            BoundedFailureReporter.FailureTarget failureTarget = BoundedFailureReporter.captureFailureTarget();
            exit.whenComplete((ignored, terminalFailure) ->
                    BoundedFailureReporter.shared().report(failureTarget, failure));
        }
    }

    private static void recordLate(AtomicReference<Throwable> target, Throwable failure) {
        if (failure != null) {
            target.accumulateAndGet(
                    failure,
                    (current, next) ->
                            FailureAggregation.combine(current, next, "Multiple late stream-session failures"));
        }
    }

    private void beginStopping() {
        state.stop();
        listenerDispatcher.stop();
    }

    private Throwable closeOutputPumpsCollecting(Throwable primary) {
        try {
            if (primary == null) {
                outputPumps.closeSession();
            } else {
                outputPumps.closeSessionPreserving(primary);
            }
            return primary;
        } catch (RuntimeException | Error closeFailure) {
            return FailureAggregation.combine(primary, closeFailure, "Multiple stream-session shutdown failures");
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (!(failure instanceof CompletionException)) {
            return failure;
        }
        try {
            Throwable cause = failure.getCause();
            return cause == null ? failure : cause;
        } catch (Throwable ignored) {
            return failure;
        }
    }

    private static java.util.Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private StreamTranscript streamTranscript() {
        BoundedTranscriptBuffer.Snapshot snapshot = diagnostics.snapshot();
        return new StreamTranscript(snapshot.text(), snapshot.truncated());
    }

    record Dependencies(ZeroReadBackoff zeroReadBackoff, PumpStarter pumpStarter, LongSupplier nanoTime) {

        Dependencies {
            Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
            Objects.requireNonNull(pumpStarter, "pumpStarter");
            Objects.requireNonNull(nanoTime, "nanoTime");
        }

        static Dependencies defaults() {
            return new Dependencies(ZeroReadBackoff.exponential(), PumpStarter.threading(), System::nanoTime);
        }
    }
}
