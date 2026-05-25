package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.command.CommandExecutionException;
import io.github.ulviar.icli.command.CommandInput;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.diagnostics.CommandEcho;
import io.github.ulviar.icli.diagnostics.DiagnosticEventType;
import io.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import io.github.ulviar.icli.internal.DiagnosticEmitter;
import io.github.ulviar.icli.internal.DurationSupport;
import io.github.ulviar.icli.internal.ProcessLifecycle;
import io.github.ulviar.icli.internal.Threading;
import io.github.ulviar.icli.session.Expect;
import io.github.ulviar.icli.session.ExpectOptions;
import io.github.ulviar.icli.session.LineSession;
import io.github.ulviar.icli.session.Session;
import io.github.ulviar.icli.session.SessionExit;
import io.github.ulviar.icli.session.StreamSession;
import io.github.ulviar.icli.terminal.TerminalSignal;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw handle for an interactive command process.
 *
 * <p>A session exposes process streams directly and owns process lifecycle coordination. It does not serialize
 * line-oriented request/response workflows; higher-level scenarios should build those guarantees on top of this raw
 * handle.
 */
public final class DefaultSession implements Session {

    private final Process process;
    private final ShutdownPolicy shutdownPolicy;
    private final Charset charset;
    private final Duration idleTimeout;
    private final DiagnosticEmitter diagnostics;
    private final SessionStdin stdin;
    private final InputStream stdout;
    private final InputStream stderr;
    private final CompletableFuture<SessionExit> exit = new CompletableFuture<>();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    private final SessionOutputOwnership outputOwnership = new SessionOutputOwnership();
    private final Object stdinLock = new Object();

    public DefaultSession(Process process, Duration idleTimeout, ShutdownPolicy shutdownPolicy, Charset charset) {
        this(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                DiagnosticEmitter.of(DiagnosticsOptions.defaults(), "session", CommandEcho.empty()));
    }

    public DefaultSession(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics) {
        this.process = Objects.requireNonNull(process, "process");
        this.idleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
        this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.stdin = new SessionStdin(process.getOutputStream());
        this.stdout = new ActivityInputStream(process.getInputStream(), this::markActivity);
        this.stderr = new ActivityInputStream(process.getErrorStream(), this::markActivity);

        startExitWatcher();
        startIdleWatcher();
    }

    /**
     * Returns raw process stdout.
     *
     * <p>The returned stream is usable only while no higher-level iCLI helper owns this session output. The first
     * consuming or lifecycle operation on a public stdout or stderr stream selects raw public-stream mode for this
     * session. After {@link Expect}, {@link LineSession}, or {@link StreamSession} claims output ownership, public stream
     * consuming and lifecycle operations fail with {@link IllegalStateException}.
     *
     * @return stdout stream
     */
    public InputStream stdout() {
        return outputOwnership.publicStream(stdout);
    }

    /**
     * Returns raw process stderr.
     *
     * <p>The returned stream is usable only while no higher-level iCLI helper owns this session output. The first
     * consuming or lifecycle operation on a public stdout or stderr stream selects raw public-stream mode for this
     * session. After {@link Expect}, {@link LineSession}, or {@link StreamSession} claims output ownership, public stream
     * consuming and lifecycle operations fail with {@link IllegalStateException}.
     *
     * @return stderr stream
     */
    public InputStream stderr() {
        return outputOwnership.publicStream(stderr);
    }

    /**
     * Returns raw process stdin guarded by the session lifecycle state.
     *
     * @return stdin stream
     */
    public OutputStream stdin() {
        return stdin;
    }

    /**
     * Writes text using the session charset and flushes stdin.
     *
     * @param text text to write
     */
    public void send(String text) {
        Objects.requireNonNull(text, "text");
        sendBytes(text.getBytes(charset));
    }

    /**
     * Writes a line feed terminated text line using the session charset and flushes stdin.
     *
     * @param line line text without the terminating line feed
     */
    public void sendLine(String line) {
        Objects.requireNonNull(line, "line");
        send(line + "\n");
    }

    /**
     * Writes explicit command input bytes and flushes stdin.
     *
     * @param input input bytes
     */
    public void send(CommandInput input) {
        Objects.requireNonNull(input, "input");
        sendBytes(input.copyBytes());
    }

    /**
     * Writes a terminal control signal and flushes stdin.
     *
     * <p>PTY-backed sessions normally translate these control bytes into process signals for the foreground command.
     * Pipe-backed sessions receive the same bytes as ordinary stdin.
     *
     * @param signal terminal signal
     */
    public void sendSignal(TerminalSignal signal) {
        Objects.requireNonNull(signal, "signal");
        sendBytes(signal.bytes());
    }

    /**
     * Closes process stdin. The session may keep running until the process exits or is closed.
     */
    public void closeStdin() {
        synchronized (stdinLock) {
            State current = state.get();
            if (current == State.STDIN_CLOSED || current == State.CLOSING || current == State.CLOSED) {
                return;
            }
            if (state.compareAndSet(State.RUNNING, State.STDIN_CLOSED)) {
                closeRawStdin();
                markActivity();
            }
        }
    }

    /**
     * Returns a process exit future view. The returned future completes once, for natural exit, explicit close, or
     * timeout, but caller-side completion does not affect the session lifecycle owner.
     *
     * @return process exit future
     */
    public CompletableFuture<SessionExit> onExit() {
        return exit.copy();
    }

    /**
     * Creates an expect automation helper using default options.
     *
     * @return expect helper
     */
    public Expect expect() {
        return expect(ExpectOptions.defaults());
    }

    /**
     * Creates an expect automation helper using explicit options.
     *
     * @param options expect options
     * @return expect helper
     */
    public Expect expect(ExpectOptions options) {
        return Expect.on(this, options);
    }

    /**
     * Stops the process through the configured shutdown policy. Calling this method more than once has no effect.
     */
    @Override
    public void close() {
        try {
            stop(false);
        } finally {
            closeResources();
        }
    }

    private void sendBytes(byte[] bytes) {
        try {
            stdin.write(bytes);
            stdin.flush();
        } catch (IOException exception) {
            throw new CommandExecutionException("Could not write session stdin", exception);
        }
    }

    private void startExitWatcher() {
        Threading.start("icli-session-exit-", () -> {
            try {
                int exitCode = process.waitFor();
                completeNaturalExit(exitCode);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                completeExceptionally(
                        new CommandExecutionException("Interrupted while waiting for session completion", exception));
            }
        });
    }

    private void startIdleWatcher() {
        if (idleTimeout.isZero()) {
            return;
        }

        long idleTimeoutNanos = DurationSupport.saturatedNanos(idleTimeout);
        Threading.start("icli-session-idle-timeout-", () -> {
            while (!exit.isDone()) {
                long elapsedNanos = System.nanoTime() - lastActivityNanos.get();
                long remainingNanos = idleTimeoutNanos - elapsedNanos;
                if (remainingNanos <= 0) {
                    stop(true);
                    return;
                }
                sleepNanos(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100)));
            }
        });
    }

    private void stop(boolean timedOut) {
        if (!transitionToClosing()) {
            return;
        }

        try {
            diagnostics.emit(
                    DiagnosticEventType.SHUTDOWN_REQUESTED,
                    DiagnosticEmitter.attributes("reason", timedOut ? "idleTimeout" : "close"));
            OptionalInt exitCode = process.isAlive()
                    ? ProcessLifecycle.stop(process, shutdownPolicy)
                    : OptionalInt.of(process.exitValue());
            diagnostics.emit(DiagnosticEventType.PROCESS_EXITED, exitAttributes(exitCode, timedOut));
            complete(new SessionExit(exitCode, timedOut));
        } catch (RuntimeException exception) {
            diagnostics.emit(
                    DiagnosticEventType.PROCESS_FAILED,
                    DiagnosticEmitter.attributes("error", exception.getClass().getName()));
            completeExceptionally(exception);
            if (!timedOut) {
                throw exception;
            }
        } finally {
            closeResources();
        }
    }

    private boolean transitionToClosing() {
        while (true) {
            State current = state.get();
            if (current == State.CLOSING || current == State.CLOSED) {
                return false;
            }
            if (state.compareAndSet(current, State.CLOSING)) {
                return true;
            }
        }
    }

    private void complete(SessionExit sessionExit) {
        state.set(State.CLOSED);
        exit.complete(sessionExit);
    }

    private void completeNaturalExit(int exitCode) {
        while (true) {
            State current = state.get();
            if (current == State.CLOSING || current == State.CLOSED) {
                return;
            }
            if (state.compareAndSet(current, State.CLOSED)) {
                diagnostics.emit(DiagnosticEventType.PROCESS_EXITED, exitAttributes(OptionalInt.of(exitCode), false));
                exit.complete(new SessionExit(OptionalInt.of(exitCode), false));
                return;
            }
        }
    }

    private void completeExceptionally(RuntimeException exception) {
        state.set(State.CLOSED);
        exit.completeExceptionally(exception);
    }

    private void markActivity() {
        lastActivityNanos.set(System.nanoTime());
    }

    void claimOutputOwner(String owner) {
        State current = state.get();
        if (current == State.CLOSING || current == State.CLOSED) {
            throw new IllegalStateException("Session is closed");
        }
        outputOwnership.claim(owner);
    }

    InputStream ownedStdout(String owner) {
        ensureOutputOwnedBy(owner);
        return stdout;
    }

    InputStream ownedStderr(String owner) {
        ensureOutputOwnedBy(owner);
        return stderr;
    }

    long pid() {
        return process.pid();
    }

    private static java.util.Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private void ensureCanWrite() {
        ensureStdinOpen();
        if (!process.isAlive()) {
            throw new IllegalStateException("Session stdin is closed");
        }
    }

    private void ensureStdinOpen() {
        if (state.get() != State.RUNNING) {
            throw new IllegalStateException("Session stdin is closed");
        }
    }

    private void ensureOutputOwnedBy(String owner) {
        outputOwnership.ensureOwnedBy(owner);
    }

    private void closeRawStdin() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Closing stdin is idempotent at the session API level.
        }
    }

    private void closeResources() {
        if (resourcesClosed.compareAndSet(false, true)) {
            closeRawStdin();
            ProcessLifecycle.closeQuietly(process.getInputStream());
            ProcessLifecycle.closeQuietly(process.getErrorStream());
        }
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }

    private static void sleepNanos(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(Math.max(1, nanos));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private enum State {
        RUNNING,
        STDIN_CLOSED,
        CLOSING,
        CLOSED
    }

    private final class SessionStdin extends OutputStream {

        private final OutputStream delegate;

        private SessionStdin(OutputStream delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void write(int value) throws IOException {
            synchronized (stdinLock) {
                ensureCanWrite();
                delegate.write(value);
                markActivity();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return;
            }
            synchronized (stdinLock) {
                ensureCanWrite();
                delegate.write(bytes, offset, length);
                markActivity();
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (stdinLock) {
                ensureStdinOpen();
                delegate.flush();
            }
        }

        @Override
        public void close() {
            closeStdin();
        }
    }

    private static final class ActivityInputStream extends FilterInputStream {

        private final Runnable activity;

        private ActivityInputStream(InputStream delegate, Runnable activity) {
            super(Objects.requireNonNull(delegate, "delegate"));
            this.activity = Objects.requireNonNull(activity, "activity");
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                activity.run();
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                activity.run();
            }
            return count;
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            byte[] bytes = super.readAllBytes();
            if (bytes.length > 0) {
                activity.run();
            }
            return bytes;
        }

        @Override
        public byte[] readNBytes(int length) throws IOException {
            byte[] bytes = super.readNBytes(length);
            if (bytes.length > 0) {
                activity.run();
            }
            return bytes;
        }

        @Override
        public int readNBytes(byte[] bytes, int offset, int length) throws IOException {
            int count = super.readNBytes(bytes, offset, length);
            if (count > 0) {
                activity.run();
            }
            return count;
        }

        @Override
        public long transferTo(OutputStream output) throws IOException {
            long count = super.transferTo(output);
            if (count > 0) {
                activity.run();
            }
            return count;
        }
    }
}
