/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.session.StreamChunk;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamListener;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Handle for a listen-only streaming command.
 *
 * <p>A stream session owns stdout/stderr pumps and dispatches chunks to the configured listener. It does not retain all
 * output; only a bounded diagnostic window is kept for exit and failure signals.
 */
public final class DefaultStreamSession implements StreamSession {

    private static final String OUTPUT_OWNER = "StreamSession";
    private static final int ZERO_READ_BACKOFF_STEPS = 8;

    private final DefaultSession session;
    private final Duration timeout;
    private final CharsetPolicy charsetPolicy;
    private final ZeroReadBackoff zeroReadBackoff;
    private final OutputPumpCoordinator outputPumps;
    private final StreamListener listener;
    private final DiagnosticEmitter eventDiagnostics;
    private final BoundedTranscriptBuffer diagnostics;
    private final LongSupplier nanoTime;
    private final long startedNanos;
    private final CompletableFuture<StreamExit> exit = new CompletableFuture<>();
    private final BoundedLifecyclePublisher.Permit exitPublication;
    private final AtomicInteger pumpsRemaining = new AtomicInteger(2);
    private final AtomicReference<NestedSessionTerminal> nestedSessionTerminal = new AtomicReference<>();
    private final TerminalArbiter terminalArbiter = new TerminalArbiter();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean truncationEmitted = new AtomicBoolean();
    private final AtomicBoolean terminalPublished = new AtomicBoolean();
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final BoundedTaskRunner.CancellationSignal listenerCancellation =
            new BoundedTaskRunner.CancellationSignal();
    private final StreamListenerTaskOwner listenerOwner = new StreamListenerTaskOwner();
    private final AtomicReference<Thread> timeoutWatcher = new AtomicReference<>();
    private final CompletableFuture<Void> timeoutWatcherStopped = new CompletableFuture<>();

    DefaultStreamSession(DefaultSession session, StreamExecutionPlan plan, DiagnosticEmitter eventDiagnostics) {
        this(session, plan, eventDiagnostics, ZeroReadBackoff.exponential(), PumpStarter.threading(), System::nanoTime);
    }

    DefaultStreamSession(
            DefaultSession session,
            StreamExecutionPlan plan,
            DiagnosticEmitter eventDiagnostics,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter) {
        this(session, plan, eventDiagnostics, zeroReadBackoff, pumpStarter, System::nanoTime);
    }

    DefaultStreamSession(
            DefaultSession session,
            StreamExecutionPlan plan,
            DiagnosticEmitter eventDiagnostics,
            ZeroReadBackoff zeroReadBackoff,
            PumpStarter pumpStarter,
            LongSupplier nanoTime) {
        this.session = Objects.requireNonNull(session, "session");
        Objects.requireNonNull(plan, "plan");
        this.timeout = plan.timeout();
        this.charsetPolicy = CharsetPolicy.replace(plan.sessionPlan().charset());
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.outputPumps = new OutputPumpCoordinator(session, OUTPUT_OWNER);
        this.listener = plan.listener();
        this.eventDiagnostics = Objects.requireNonNull(eventDiagnostics, "eventDiagnostics");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.startedNanos = nanoTime.getAsLong();
        this.diagnostics = new BoundedTranscriptBuffer(plan.diagnosticLimit());
        BoundedLifecyclePublisher.Reservation publicationReservation =
                BoundedLifecyclePublisher.shared().reserve(1);
        this.exitPublication = publicationReservation.takePermit();
        boolean pumpsCommitted = false;
        try {
            startPumps(Objects.requireNonNull(pumpStarter, "pumpStarter"));
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
        return timeoutWatcherStopped.copy();
    }

    CompletableFuture<Void> physicalOutputCleanup() {
        return session.physicalOutputCleanup();
    }

    boolean outputCleanupCompleted() {
        return outputPumps.outputCleanupCompleted();
    }

    /**
     * Stops the underlying process through the configured shutdown policy. Calling this method more than once has no
     * effect.
     */
    @Override
    public void close() {
        TerminalSelection selection = selectControlOutcome(new ClosedOutcome());
        if (selection.installed()) {
            Throwable failure = null;
            if (!session.exitCompleted()) {
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
        IncrementalTextDecoder decoder = null;
        AtomicReference<Throwable> lateFailure = new AtomicReference<>();
        try (stream) {
            int configuredLimit = diagnostics.limit();
            decoder = new IncrementalTextDecoder(
                    charsetPolicy,
                    IncrementalTextDecoder.pendingByteLimitFor(configuredLimit),
                    IncrementalTextDecoder.outputWithoutInputLimitFor(configuredLimit));
            IncrementalTextDecoder activeDecoder = decoder;
            IncrementalTextDecoder.Sink sink =
                    (chars, count) -> recordLate(lateFailure, publishDecoded(source, chars, count));
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
            }
            if (!stopping.get()) {
                activeDecoder.end(sink);
            }
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
            if (pumpsRemaining.decrementAndGet() == 0) {
                listenerOwner.close();
                maybeComplete();
            }
            reportLate(lateFailure.get());
        }
    }

    private Throwable publishDecoded(StreamSource source, char[] chars, int count) {
        if (stopping.get()) {
            return null;
        }
        String text = new String(chars, 0, count);
        boolean truncated = diagnostics.appendStream(source.label(), text);
        if (truncated && truncationEmitted.compareAndSet(false, true)) {
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
        deliveryLock.lock();
        try {
            if (stopping.get() || terminalArbiter.outcome() != null) {
                return null;
            }
            try {
                BoundedTaskRunner.runReportingLateFailure(
                        BoundedTaskRunner.STREAM_LISTENERS,
                        "procwright-stream-listener-",
                        Long.MAX_VALUE,
                        listenerCancellation,
                        this::reportLate,
                        listenerOwner,
                        () -> {
                            listener.onChunk(chunk);
                            return null;
                        });
                return null;
            } catch (BoundedTaskRunner.TaskCancelledException cancelled) {
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
        } finally {
            deliveryLock.unlock();
        }
    }

    private void startExitWatcher() {
        session.observeExit((value, throwable) -> {
            if (throwable == null) {
                nestedSessionTerminal.set(new NestedSessionSuccess(value));
            } else {
                Throwable processFailure = unwrapCompletionFailure(throwable);
                nestedSessionTerminal.set(new NestedSessionFailure(processFailure));
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
        if (timeout.isZero()) {
            timeoutWatcherStopped.complete(null);
            return;
        }
        Thread watcher = Threading.unstarted("procwright-stream-timeout-", () -> {
            boolean expired = false;
            try {
                expired = sleep(timeout);
            } finally {
                timeoutWatcher.compareAndSet(Thread.currentThread(), null);
                timeoutWatcherStopped.complete(null);
            }
            if (expired) {
                expireTimeout();
            }
        });
        timeoutWatcher.set(watcher);
        try {
            watcher.start();
        } catch (RuntimeException | Error startFailure) {
            timeoutWatcher.compareAndSet(watcher, null);
            timeoutWatcherStopped.complete(null);
            throw startFailure;
        }
        if (terminalArbiter.outcome() != null) {
            stopTimeoutWatcher();
        }
    }

    void expireTimeout() {
        TerminalSelection selection = selectControlOutcome(new TimedOutOutcome());
        if (!selection.installed()) {
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
        TerminalSelection selection = selectFailure(exception);
        if (selection.installed() && reason == StreamException.Reason.LISTENER_FAILED) {
            emitPreserving(DiagnosticEventType.LISTENER_FAILED, exception);
        }
        activate(selection);
        return selection.lateFailure();
    }

    private Throwable failFatal(Error error) {
        TerminalSelection claim = terminalArbiter.claim(new FatalFailure(error));
        TerminalOutcome outcome = claim.outcome();
        TerminalSelection selection;
        if (claim.installed()) {
            beginStopping();
            selection = new TerminalSelection(outcome, true, null);
        } else if (outcome instanceof FailureOutcome failureOutcome) {
            SuppressionSupport.attach(failureOutcome.primary(), error);
            selection = new TerminalSelection(outcome, false, null);
        } else {
            selection = new TerminalSelection(outcome, false, error);
        }
        activate(selection);
        return selection.lateFailure();
    }

    private TerminalSelection selectFailure(StreamException candidate) {
        TerminalSelection claim = terminalArbiter.claim(new TypedFailure(candidate));
        TerminalOutcome outcome = claim.outcome();
        if (claim.installed()) {
            beginStopping();
            return new TerminalSelection(outcome, true, null);
        }
        if (outcome instanceof FailureOutcome failureOutcome) {
            SuppressionSupport.attach(failureOutcome.primary(), candidate);
            return new TerminalSelection(outcome, false, null);
        }
        return new TerminalSelection(outcome, false, candidate);
    }

    private void activate(TerminalSelection selection) {
        if (!selection.installed()) {
            maybeComplete();
            return;
        }
        Throwable primary = ((FailureOutcome) selection.outcome()).primary();
        emitPreserving(DiagnosticEventType.PROCESS_FAILED, DiagnosticEmitter.failureAttributes(primary), primary);
        emitPreserving(
                DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "failure"), primary);
        outputPumps.closeSessionPreserving(primary);
        maybeComplete();
    }

    private void maybeComplete() {
        TerminalOutcome outcome = terminalArbiter.outcome();
        if (outcome instanceof FailureOutcome failureOutcome && pumpsRemaining.get() == 0) {
            publishFailure(failureOutcome.primary());
            return;
        }

        NestedSessionTerminal nestedTerminal = nestedSessionTerminal.get();
        if (nestedTerminal == null || pumpsRemaining.get() != 0) {
            return;
        }

        if (outcome == null) {
            if (nestedTerminal instanceof NestedSessionFailure) {
                return;
            }
            TerminalSelection normal = selectControlOutcome(new NormalOutcome());
            outcome = normal.outcome();
        }
        if (outcome instanceof FailureOutcome failureOutcome) {
            publishFailure(failureOutcome.primary());
            return;
        }
        OptionalInt exitCode = nestedTerminal.exitCode();
        boolean timedOut = outcome instanceof TimedOutOutcome || nestedTerminal.timedOut();
        boolean closed = outcome instanceof ClosedOutcome;
        if (!terminalPublished.compareAndSet(false, true)) {
            return;
        }
        stopTimeoutWatcherBeforePublication();
        outputPumps.publishAfterOutputCleanup(() -> exitPublication.publish(() -> {
            session.awaitPhysicalOutputPublication();
            StreamExit terminal = new StreamExit(
                    exitCode,
                    timedOut,
                    closed,
                    streamTranscript(),
                    DurationSupport.elapsed(startedNanos, nanoTime.getAsLong()));
            Throwable diagnosticFailure =
                    emitCollecting(DiagnosticEventType.PROCESS_EXITED, exitAttributes(exitCode, timedOut), null);
            exit.complete(terminal);
            reportLate(diagnosticFailure);
        }));
    }

    private void publishFailure(Throwable primary) {
        if (!terminalPublished.compareAndSet(false, true)) {
            return;
        }
        stopTimeoutWatcherBeforePublication();
        outputPumps.publishAfterOutputCleanup(() -> exitPublication.publish(() -> {
            session.awaitPhysicalOutputPublication();
            exit.completeExceptionally(primary);
        }));
    }

    private TerminalSelection selectControlOutcome(TerminalOutcome candidate) {
        TerminalSelection selection = terminalArbiter.claim(candidate);
        if (selection.installed()) {
            beginStopping();
        }
        return selection;
    }

    private boolean isControlledStop() {
        TerminalOutcome outcome = terminalArbiter.outcome();
        return outcome instanceof ClosedOutcome || outcome instanceof TimedOutOutcome;
    }

    private void stopTimeoutWatcher() {
        Thread watcher = timeoutWatcher.getAndSet(null);
        if (watcher != null) {
            watcher.interrupt();
        }
    }

    private void stopTimeoutWatcherBeforePublication() {
        Thread watcher = timeoutWatcher.getAndSet(null);
        if (watcher != null && watcher != Thread.currentThread()) {
            watcher.interrupt();
        }
        if (watcher != Thread.currentThread()) {
            timeoutWatcherStopped.join();
        }
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
            return SuppressionSupport.combine(primary, diagnosticFailure);
        }
    }

    private void reportLate(Throwable failure) {
        if (failure != null) {
            BoundedFailureReporter.FailureTarget failureTarget = BoundedFailureReporter.captureFailureTarget();
            exit.whenComplete((ignored, terminalFailure) ->
                    BoundedFailureReporter.shared().report(failureTarget, failure));
        }
    }

    private void reportLate(Thread sourceThread, Throwable failure) {
        if (failure != null) {
            BoundedFailureReporter.FailureTarget failureTarget =
                    BoundedFailureReporter.captureFailureTarget(sourceThread);
            exit.whenComplete((ignored, terminalFailure) ->
                    BoundedFailureReporter.shared().report(failureTarget, failure));
        }
    }

    private static void recordLate(AtomicReference<Throwable> target, Throwable failure) {
        if (failure != null) {
            target.accumulateAndGet(failure, SuppressionSupport::combine);
        }
    }

    private void beginStopping() {
        stopping.set(true);
        listenerCancellation.cancel();
        listenerOwner.close();
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
            return SuppressionSupport.combine(primary, closeFailure);
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

    private static boolean sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(DurationSupport.saturatedNanos(duration));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
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

    private sealed interface TerminalOutcome permits NormalOutcome, TimedOutOutcome, ClosedOutcome, FailureOutcome {}

    private sealed interface FailureOutcome extends TerminalOutcome permits TypedFailure, FatalFailure {

        Throwable primary();
    }

    private record NormalOutcome() implements TerminalOutcome {}

    private record TimedOutOutcome() implements TerminalOutcome {}

    private record ClosedOutcome() implements TerminalOutcome {}

    private record TypedFailure(StreamException primary) implements FailureOutcome {}

    private record FatalFailure(Error primary) implements FailureOutcome {}

    private record TerminalSelection(TerminalOutcome outcome, boolean installed, Throwable lateFailure) {}

    private sealed interface NestedSessionTerminal permits NestedSessionSuccess, NestedSessionFailure {

        OptionalInt exitCode();

        boolean timedOut();
    }

    private record NestedSessionSuccess(SessionExit exit) implements NestedSessionTerminal {

        private NestedSessionSuccess {
            Objects.requireNonNull(exit, "exit");
        }

        @Override
        public OptionalInt exitCode() {
            return exit.exitCode();
        }

        @Override
        public boolean timedOut() {
            return exit.timedOut();
        }
    }

    private record NestedSessionFailure(Throwable failure) implements NestedSessionTerminal {

        private NestedSessionFailure {
            Objects.requireNonNull(failure, "failure");
        }

        @Override
        public OptionalInt exitCode() {
            return OptionalInt.empty();
        }

        @Override
        public boolean timedOut() {
            return false;
        }
    }

    private static final class TerminalArbiter {

        private final AtomicReference<TerminalOutcome> outcome = new AtomicReference<>();

        private TerminalSelection claim(TerminalOutcome candidate) {
            Objects.requireNonNull(candidate, "candidate");
            while (true) {
                TerminalOutcome existing = outcome.get();
                if (existing != null) {
                    return new TerminalSelection(existing, false, null);
                }
                if (outcome.compareAndSet(null, candidate)) {
                    return new TerminalSelection(candidate, true, null);
                }
            }
        }

        private TerminalOutcome outcome() {
            return outcome.get();
        }
    }
}
