/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.session.StreamChunk;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.session.StreamSource;
import io.github.ulviar.procwright.session.StreamTranscript;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Handle for a listen-only streaming command.
 *
 * <p>A stream session owns stdout/stderr pumps and dispatches chunks to the configured listener. It does not retain all
 * output; only a bounded diagnostic window is kept for exit and failure signals.
 */
public final class DefaultStreamSession implements StreamSession {

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
    private final StreamTimeoutWatcher timeoutWatcher = new StreamTimeoutWatcher();
    private final AtomicBoolean stopping = new AtomicBoolean();

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
        this.outputPumps = new OutputPumpCoordinator(session, SessionOutputMode.STREAM);
        this.outputReader = new StreamOutputReader(
                CharsetPolicy.replace(plan.sessionPlan().charset()), plan.diagnosticLimit(), runtime.zeroReadBackoff());
        this.listenerDispatcher = new StreamListenerDispatcher(plan.listener());
        this.eventDiagnostics = Objects.requireNonNull(eventDiagnostics, "eventDiagnostics");
        this.nanoTime = runtime.nanoTime();
        this.startedNanos = nanoTime.getAsLong();
        this.diagnostics = new BoundedTranscriptBuffer(plan.diagnosticLimit());
        try {
            startPumps(runtime.pumpStarter());
            startTimeoutWatcher();
            startExitWatcher();
            session.closeStdin();
        } catch (RuntimeException | Error failure) {
            abortStartup();
            throw failure;
        }
    }

    /**
     * Returns a stream-session exit future view. It completes after process supervision and logical stream-mode
     * settlement; potentially blocking physical stream closes continue independently.
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

    /**
     * Stops the underlying process through the configured shutdown policy. Calling this method more than once has no
     * effect.
     */
    @Override
    public void close() {
        stop(false);
    }

    private void startPumps(PumpStarter pumpStarter) {
        outputPumps.start(
                pumpStarter,
                "procwright-stream-stdout-",
                stream -> pump(StreamSource.STDOUT, stream),
                "procwright-stream-stderr-",
                stream -> pump(StreamSource.STDERR, stream));
    }

    private void pump(StreamSource source, InputStream stream) {
        try {
            outputReader.read(stream, stopping::get, (chars, count) -> publishDecoded(source, chars, count));
        } catch (IOException exception) {
            if (!stopping.get()) {
                if (exception instanceof IncrementalTextDecoder.DecoderStateException
                        && exception.getCause() instanceof Error decoderError) {
                    failFatal(decoderError);
                } else {
                    failOutputRead(exception);
                }
            }
        } catch (RuntimeException exception) {
            if (!stopping.get()) {
                failOutputRead(exception);
            }
        } catch (Error error) {
            failFatal(error);
        }
    }

    private void publishDecoded(StreamSource source, char[] chars, int count) {
        if (stopping.get()) {
            return;
        }
        String text = new String(chars, 0, count);
        boolean truncated = diagnostics.appendStream(source.label(), text);
        if (truncated) {
            eventDiagnostics.emitBestEffort(
                    DiagnosticEventType.OUTPUT_TRUNCATED,
                    DiagnosticEmitter.attributes(
                            "source", "diagnostics", "limitChars", Integer.toString(diagnostics.limit())));
        }
        deliver(new StreamChunk(source, text));
    }

    private void failOutputRead(Throwable failure) {
        recordFailure(StreamException.Reason.OUTPUT_READ_FAILED, "Could not read streaming output", failure);
    }

    private void deliver(StreamChunk chunk) {
        try {
            listenerDispatcher.deliver(chunk);
        } catch (RuntimeException failure) {
            recordFailure(StreamException.Reason.LISTENER_FAILED, "Could not invoke streaming listener", failure);
        } catch (Error error) {
            failFatal(error);
        }
    }

    private void startExitWatcher() {
        session.observePublicOutcome(this::publish);
    }

    private void startTimeoutWatcher() {
        timeoutWatcher.start(timeout, session::terminationPublished, this::expireTimeout);
    }

    void expireTimeout() {
        outputPumps.closeSession(true, () -> {
            beginStopping();
            emitBestEffort(DiagnosticEventType.TIMEOUT_REACHED);
            stopTimeoutWatcher();
        });
    }

    private void recordFailure(StreamException.Reason reason, String message, Throwable cause) {
        StreamException exception = new StreamException(reason, message, streamTranscript(), cause);
        boolean selected = outputPumps.closeSessionAfterFailure(exception, this::beginStopping);
        if (selected && reason == StreamException.Reason.LISTENER_FAILED) {
            emitBestEffort(DiagnosticEventType.LISTENER_FAILED);
        }
    }

    private void failFatal(Error error) {
        outputPumps.closeSessionAfterFailure(error, this::beginStopping);
    }

    private void stop(boolean timedOut) {
        outputPumps.closeSession(timedOut, () -> {
            beginStopping();
            stopTimeoutWatcher();
        });
    }

    private void publish(SessionTerminal.PublicOutcome outcome) {
        beginStopping();
        stopTimeoutWatcher();
        Throwable failure = streamFailure(outcome.failure());
        if (failure != null) {
            exit.completeExceptionally(failure);
            return;
        }
        var terminal = outcome.result();
        exit.complete(new StreamExit(
                terminal.exitCode(),
                terminal.timedOut(),
                outcome.successKind() == SessionTerminal.SuccessKind.CLOSED,
                streamTranscript(),
                DurationSupport.elapsed(startedNanos, nanoTime.getAsLong())));
    }

    private Throwable streamFailure(Throwable processFailure) {
        if (processFailure == null) {
            return null;
        }
        if (processFailure instanceof Error || processFailure instanceof StreamException) {
            return processFailure;
        }
        return new StreamException(
                StreamException.Reason.PROCESS_FAILED, "Streaming process failed", streamTranscript(), processFailure);
    }

    private void stopTimeoutWatcher() {
        timeoutWatcher.stop();
    }

    private void abortStartup() {
        beginStopping();
        stopTimeoutWatcher();
    }

    private void emitBestEffort(DiagnosticEventType type) {
        eventDiagnostics.emitBestEffort(type);
    }

    private boolean beginStopping() {
        if (stopping.compareAndSet(false, true)) {
            listenerDispatcher.stop();
            return true;
        }
        return false;
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
