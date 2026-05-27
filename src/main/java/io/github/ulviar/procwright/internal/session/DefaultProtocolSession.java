package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.SessionExit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Generic request/response workflow over an interactive process.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class DefaultProtocolSession<I, O> implements ProtocolSession<I, O> {

    private static final String OUTPUT_OWNER = "ProtocolSession";
    private final DefaultSession session;
    private final ProtocolAdapter<I, O> adapter;
    private final ProtocolSessionOptions options;
    private final ProtocolTranscriptBuffer transcript;
    private final ProtocolOutputQueue stdout;
    private final ProtocolOutputQueue stderr;
    private final ReentrantLock requestLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ProtocolRuntimeFailures failures = new ProtocolRuntimeFailures() {
        @Override
        public ProtocolSessionException timeout(Throwable cause) {
            return DefaultProtocolSession.this.timeout(cause);
        }

        @Override
        public ProtocolSessionException closed(Throwable cause) {
            return DefaultProtocolSession.this.closed(cause);
        }

        @Override
        public ProtocolSessionException eof() {
            return DefaultProtocolSession.this.eof();
        }

        @Override
        public ProtocolSessionException failure(
                ProtocolSessionException.Reason reason, String message, Throwable cause) {
            return DefaultProtocolSession.this.failure(reason, message, cause);
        }
    };

    public DefaultProtocolSession(
            DefaultSession session, ProtocolAdapter<I, O> adapter, ProtocolSessionOptions options) {
        this.session = Objects.requireNonNull(session, "session");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.options = Objects.requireNonNull(options, "options");
        this.transcript = new ProtocolTranscriptBuffer(options.transcriptLimit(), options.charsetPolicy());
        this.stdout = new ProtocolOutputQueue(options.stdoutBacklogLimit());
        this.stderr = new ProtocolOutputQueue(options.stdoutBacklogLimit());
        this.session.claimOutputOwner(OUTPUT_OWNER);
        startPumps();
    }

    @Override
    public O request(I request) {
        return request(request, options.requestTimeout());
    }

    @Override
    public O request(I request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        Duration requestTimeout = requirePositive(timeout, "timeout");
        requestLock.lock();
        try {
            ensureOpen();
            long deadlineNanos = DurationSupport.deadlineFromNow(requestTimeout);
            writeRequest(request, deadlineNanos);
            return readResponse(deadlineNanos);
        } catch (ProtocolSessionException exception) {
            if (exception.reason() != ProtocolSessionException.Reason.CLOSED) {
                closePreserving(exception);
            }
            throw exception;
        } finally {
            requestLock.unlock();
        }
    }

    @Override
    public ProtocolTranscript transcript() {
        return protocolTranscript();
    }

    @Override
    public CompletableFuture<SessionExit> onExit() {
        return session.onExit();
    }

    @Override
    public void close() {
        closeWithEvent(true);
    }

    private void closeWithEvent(boolean publishClosed) {
        if (closed.compareAndSet(false, true)) {
            if (publishClosed) {
                stdout.close();
                stderr.close();
            }
            session.close();
        }
    }

    private void startPumps() {
        startPump("stdout", session.ownedStdout(OUTPUT_OWNER), stdout);
        startPump("stderr", session.ownedStderr(OUTPUT_OWNER), stderr);
    }

    private void startPump(String streamName, InputStream stream, ProtocolOutputQueue output) {
        Threading.start("procwright-protocol-" + streamName + "-", () -> pump(streamName, stream, output));
    }

    private void pump(String streamName, InputStream stream, ProtocolOutputQueue output) {
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                transcript.appendStream(streamName, buffer, count);
                if (!output.offer(Arrays.copyOf(buffer, count))) {
                    failOutputBacklogOverflow();
                    closeQuietly();
                    return;
                }
            }
            output.eof();
        } catch (IOException exception) {
            if (!closed.get()) {
                output.failure(reasonFor(exception), exception);
            }
        }
    }

    private void writeRequest(I request, long deadlineNanos) {
        CompletableFuture<Void> written = new CompletableFuture<>();
        Thread writer = Threading.start("procwright-protocol-stdin-", () -> {
            try {
                adapter.writeRequest(request, new ProtocolRequestWriter(session, options, deadlineNanos, failures));
                written.complete(null);
            } catch (Throwable throwable) {
                written.completeExceptionally(throwable);
            }
        });
        try {
            written.get(DurationSupport.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            writer.interrupt();
            throw timeout(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.interrupt();
            throw failure(
                    ProtocolSessionException.Reason.FAILURE, "Interrupted while writing protocol request", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ProtocolSessionException protocolException) {
                throw protocolException;
            }
            if (cause instanceof IOException ioException) {
                throw failure(
                        ProtocolSessionException.Reason.BROKEN_PIPE, "Could not write protocol request", ioException);
            }
            if (cause instanceof IllegalStateException illegalState) {
                throw closed(illegalState);
            }
            throw failure(ProtocolSessionException.Reason.FAILURE, "Could not write protocol request", cause);
        }
    }

    private O readResponse(long deadlineNanos) {
        try {
            ProtocolResponseBudget budget =
                    new ProtocolResponseBudget(options.maxResponseBytes(), options.maxResponseChars(), failures);
            return adapter.readResponse(new Readers(
                    new ProtocolResponseReader(stdout, options, deadlineNanos, budget, failures),
                    new ProtocolResponseReader(stderr, options, deadlineNanos, budget, failures)));
        } catch (ProtocolSessionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED,
                    "Protocol response decoder failed",
                    exception);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw closed(null);
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
        OptionalInt exitCode = exitCodeSnapshot(Duration.ofMillis(100));
        ProtocolSessionException.Reason reason = exitCode.isPresent()
                ? ProtocolSessionException.Reason.PROCESS_EXITED
                : ProtocolSessionException.Reason.EOF;
        return new ProtocolSessionException(
                reason, protocolTranscript(), exitCode, "Protocol session reached EOF", null);
    }

    private ProtocolSessionException failure(ProtocolSessionException.Reason reason, String message, Throwable cause) {
        return new ProtocolSessionException(reason, protocolTranscript(), exitCodeSnapshot(), message, cause);
    }

    private ProtocolTranscript protocolTranscript() {
        return transcript.snapshot();
    }

    private OptionalInt exitCodeSnapshot() {
        return exitCodeSnapshot(Duration.ZERO);
    }

    private OptionalInt exitCodeSnapshot(Duration wait) {
        CompletableFuture<SessionExit> exit = session.onExit();
        if (!exit.isDone()) {
            try {
                if (!wait.isZero() && !wait.isNegative()) {
                    return exit.get(DurationSupport.saturatedMillis(wait), TimeUnit.MILLISECONDS)
                            .exitCode();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return OptionalInt.empty();
            } catch (ExecutionException | TimeoutException exception) {
                return OptionalInt.empty();
            }
        }
        try {
            return exit.getNow(new SessionExit(OptionalInt.empty(), false)).exitCode();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    private void closePreserving(ProtocolSessionException exception) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            exception.addSuppressed(closeFailure);
        }
    }

    private void closeQuietly() {
        try {
            closeWithEvent(false);
        } catch (RuntimeException ignored) {
            // The reader observes the original protocol failure.
        }
    }

    private void failOutputBacklogOverflow() {
        CommandExecutionException failure = new CommandExecutionException("Protocol output backlog overflow");
        stdout.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure);
        stderr.failAndClear(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, failure);
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
}
