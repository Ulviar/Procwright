package com.github.ulviar.icli;

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

    private final Session session;
    private final Duration timeout;
    private final Charset charset;
    private final StreamListener listener;
    private final TranscriptBuffer diagnostics;
    private final Instant started = Instant.now();
    private final CompletableFuture<StreamExit> exit = new CompletableFuture<>();
    private final AtomicInteger pumpsRemaining = new AtomicInteger(2);
    private final AtomicReference<SessionExit> sessionExit = new AtomicReference<>();
    private final AtomicReference<StreamException> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final ReentrantLock deliveryLock = new ReentrantLock();

    StreamSession(Session session, StreamExecutionPlan plan) {
        this.session = Objects.requireNonNull(session, "session");
        Objects.requireNonNull(plan, "plan");
        this.timeout = plan.timeout();
        this.charset = plan.sessionPlan().charset();
        this.listener = plan.listener();
        this.diagnostics = new TranscriptBuffer(plan.diagnosticLimit());
        this.session.claimOutputOwner("StreamSession");

        startPump(StreamSource.STDOUT, session.stdout());
        startPump(StreamSource.STDERR, session.stderr());
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
        closed.set(true);
        stopping.set(true);
        session.close();
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
                diagnostics.append(source, text);
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
            return;
        }
        Thread.ofVirtual().name("icli-stream-timeout-", 0).start(() -> {
            sleep(timeout);
            if (!exit.isDone()) {
                timedOut.set(true);
                stopping.set(true);
                session.close();
            }
        });
    }

    private void recordFailure(String message, Throwable cause) {
        StreamException exception = new StreamException(message, diagnostics.snapshot(), cause);
        if (failure.compareAndSet(null, exception)) {
            stopping.set(true);
            session.close();
        }
    }

    private void maybeComplete() {
        StreamException streamFailure = failure.get();
        if (streamFailure != null && pumpsRemaining.get() == 0) {
            exit.completeExceptionally(streamFailure);
            return;
        }

        SessionExit processExit = sessionExit.get();
        if (processExit == null || pumpsRemaining.get() != 0) {
            return;
        }

        exit.complete(new StreamExit(
                processExit.exitCode(),
                timedOut.get() || processExit.timedOut(),
                closed.get(),
                diagnostics.snapshot(),
                Duration.between(started, Instant.now())));
    }

    private static void sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(saturatedNanos(duration));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
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

        private synchronized void append(StreamSource source, String chunk) {
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
        }

        private synchronized StreamTranscript snapshot() {
            return new StreamTranscript(text.toString(), truncated);
        }
    }
}
