package com.github.ulviar.icli.session;

import com.github.ulviar.icli.diagnostics.DiagnosticEventType;
import com.github.ulviar.icli.internal.DiagnosticEmitter;
import com.github.ulviar.icli.internal.DurationSupport;
import com.github.ulviar.icli.internal.StreamExecutionPlan;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Handle for a listen-only streaming command.
 *
 * <p>A stream session owns stdout/stderr pumps and dispatches chunks to the configured listener. It does not retain all
 * output; only a bounded diagnostic window is kept for exit and failure signals.
 */
public final class StreamSession implements AutoCloseable {

    private static final String OUTPUT_OWNER = "StreamSession";

    private final Session session;
    private final Duration timeout;
    private final Charset charset;
    private final StreamListener listener;
    private final DiagnosticEmitter eventDiagnostics;
    private final TranscriptBuffer diagnostics;
    private final Instant started = Instant.now();
    private final CompletableFuture<StreamExit> exit = new CompletableFuture<>();
    private final AtomicInteger pumpsRemaining = new AtomicInteger(2);
    private final AtomicReference<SessionExit> sessionExit = new AtomicReference<>();
    private final AtomicReference<StreamException> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicBoolean truncationEmitted = new AtomicBoolean();
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final AtomicReference<Thread> timeoutWatcher = new AtomicReference<>();
    private final CompletableFuture<Void> timeoutWatcherStopped = new CompletableFuture<>();

    StreamSession(Session session, StreamExecutionPlan plan, DiagnosticEmitter eventDiagnostics) {
        this.session = Objects.requireNonNull(session, "session");
        Objects.requireNonNull(plan, "plan");
        this.timeout = plan.timeout();
        this.charset = plan.sessionPlan().charset();
        this.listener = plan.listener();
        this.eventDiagnostics = Objects.requireNonNull(eventDiagnostics, "eventDiagnostics");
        this.diagnostics = new TranscriptBuffer(plan.diagnosticLimit());
        this.session.claimOutputOwner(OUTPUT_OWNER);

        startPump(StreamSource.STDOUT, session.ownedStdout(OUTPUT_OWNER));
        startPump(StreamSource.STDERR, session.ownedStderr(OUTPUT_OWNER));
        startExitWatcher();
        if (plan.stdinPolicy() == StreamStdinPolicy.CLOSE_ON_START) {
            session.closeStdin();
        }
        startTimeoutWatcher();
    }

    /**
     * Returns a process exit future view. The future completes after the process exits and output pumps drain.
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
        return diagnostics.snapshot();
    }

    CompletableFuture<Void> timeoutWatcherStopped() {
        return timeoutWatcherStopped.copy();
    }

    /**
     * Closes process stdin without writing input. This is useful when the stream was started with
     * {@link StreamInvocation.Builder#keepStdinOpen()}.
     */
    public void closeStdin() {
        session.closeStdin();
    }

    /**
     * Stops the underlying process through the configured shutdown policy. Calling this method more than once has no
     * effect.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            boolean alreadyStopping = stopping.getAndSet(true);
            if (!alreadyStopping && !session.onExit().isDone()) {
                eventDiagnostics.emit(
                        DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "close"));
            }
            stopTimeoutWatcher();
            session.close();
        }
    }

    private void startPump(StreamSource source, InputStream stream) {
        Thread.ofVirtual().name("icli-stream-" + source.label() + "-", 0).start(() -> pump(source, stream));
    }

    private void pump(StreamSource source, InputStream stream) {
        try (Reader reader = new InputStreamReader(stream, charset)) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                String text = new String(buffer, 0, count);
                boolean truncated = diagnostics.append(source, text);
                if (truncated && truncationEmitted.compareAndSet(false, true)) {
                    eventDiagnostics.emit(
                            DiagnosticEventType.OUTPUT_TRUNCATED,
                            DiagnosticEmitter.attributes(
                                    "source", "diagnostics", "limitChars", Integer.toString(diagnostics.limit())));
                }
                if (!deliver(new StreamChunk(source, text))) {
                    return;
                }
            }
        } catch (IOException exception) {
            if (!stopping.get()) {
                recordFailure("Could not read streaming output", exception);
            }
        } finally {
            if (pumpsRemaining.decrementAndGet() == 0) {
                maybeComplete();
            }
        }
    }

    private boolean deliver(StreamChunk chunk) {
        deliveryLock.lock();
        try {
            listener.onChunk(chunk);
            return true;
        } catch (RuntimeException exception) {
            eventDiagnostics.emit(DiagnosticEventType.LISTENER_FAILED);
            recordFailure("Streaming listener failed", exception);
            return false;
        } finally {
            deliveryLock.unlock();
        }
    }

    private void startExitWatcher() {
        session.onExit().whenComplete((value, throwable) -> {
            if (throwable == null) {
                sessionExit.set(value);
            } else {
                recordFailure("Streaming process failed", throwable);
            }
            maybeComplete();
        });
    }

    private void startTimeoutWatcher() {
        if (timeout.isZero()) {
            timeoutWatcherStopped.complete(null);
            return;
        }
        Thread watcher = Thread.ofVirtual().name("icli-stream-timeout-", 0).unstarted(() -> {
            try {
                if (!sleep(timeout) || exit.isDone()) {
                    return;
                }
                if (!exit.isDone()) {
                    timedOut.set(true);
                    stopping.set(true);
                    eventDiagnostics.emit(DiagnosticEventType.TIMEOUT_REACHED);
                    eventDiagnostics.emit(
                            DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "timeout"));
                    session.close();
                }
            } finally {
                timeoutWatcher.compareAndSet(Thread.currentThread(), null);
                timeoutWatcherStopped.complete(null);
            }
        });
        timeoutWatcher.set(watcher);
        watcher.start();
        if (exit.isDone()) {
            stopTimeoutWatcher();
        }
    }

    private void recordFailure(String message, Throwable cause) {
        StreamException exception = new StreamException(message, diagnostics.snapshot(), cause);
        if (failure.compareAndSet(null, exception)) {
            stopping.set(true);
            eventDiagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    DiagnosticEmitter.attributes("error", cause.getClass().getName()));
            eventDiagnostics.emit(
                    DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "failure"));
            session.close();
        }
    }

    private void maybeComplete() {
        StreamException streamFailure = failure.get();
        if (streamFailure != null && pumpsRemaining.get() == 0) {
            if (exit.completeExceptionally(streamFailure)) {
                stopTimeoutWatcher();
            }
            return;
        }

        SessionExit processExit = sessionExit.get();
        if (processExit == null || pumpsRemaining.get() != 0) {
            return;
        }

        eventDiagnostics.emit(
                DiagnosticEventType.PROCESS_EXITED,
                exitAttributes(processExit, timedOut.get() || processExit.timedOut()));
        if (exit.complete(new StreamExit(
                processExit.exitCode(),
                timedOut.get() || processExit.timedOut(),
                closed.get(),
                diagnostics.snapshot(),
                Duration.between(started, Instant.now())))) {
            stopTimeoutWatcher();
        }
    }

    private void stopTimeoutWatcher() {
        Thread watcher = timeoutWatcher.getAndSet(null);
        if (watcher != null) {
            watcher.interrupt();
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

    private static java.util.Map<String, String> exitAttributes(SessionExit processExit, boolean timedOut) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        processExit.exitCode().ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private static final class TranscriptBuffer {

        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private String currentSource;
        private boolean atLineStart = true;
        private boolean truncated;

        private TranscriptBuffer(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
        }

        private int limit() {
            return limit;
        }

        private synchronized boolean append(StreamSource source, String chunk) {
            boolean alreadyTruncated = truncated;
            for (int index = 0; index < chunk.length(); index++) {
                String label = source.label();
                if (atLineStart || !label.equals(currentSource)) {
                    if (!atLineStart) {
                        text.append('\n');
                    }
                    text.append(label).append(": ");
                    atLineStart = false;
                    currentSource = label;
                }
                char value = chunk.charAt(index);
                text.append(value);
                if (value == '\n') {
                    atLineStart = true;
                    currentSource = null;
                }
            }
            if (text.length() > limit) {
                text.delete(0, text.length() - limit);
                truncated = true;
            }
            return truncated && !alreadyTruncated;
        }

        private synchronized StreamTranscript snapshot() {
            return new StreamTranscript(text.toString(), truncated);
        }
    }
}
