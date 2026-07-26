/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.ProcessIoResources;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns session stream access, output ownership, and exact-once physical close. */
final class SessionResources {

    private final Process process;
    private final ProcessIoResources resources;
    private final SessionStdin stdin;
    private final OutputCloseReservation outputCloseReservation = new OutputCloseReservation();
    private final CloseOnceInputStream stdoutClose;
    private final CloseOnceInputStream stderrClose;
    private final InputStream stdout;
    private final InputStream stderr;
    private final SessionOutputOwnership outputOwnership;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stdinOpen = new AtomicBoolean(true);
    private final Runnable activity;
    private final Consumer<Throwable> terminalCloseFailures;

    static SessionResources acquire(
            Process process,
            SessionOutputMode outputMode,
            BoundedCloseDispatcher closeDispatcher,
            Runnable activity,
            Consumer<Throwable> terminalCloseFailures) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(closeDispatcher, "closeDispatcher");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(terminalCloseFailures, "terminalCloseFailures");
        SessionOutputOwnership outputOwnership = new SessionOutputOwnership(outputMode);
        ProcessIoResources resources = ProcessIoResources.acquire(process, closeDispatcher);
        try {
            return new SessionResources(
                    process, outputMode, outputOwnership, resources, activity, terminalCloseFailures);
        } catch (RuntimeException | Error failure) {
            rethrow(FailureAggregation.combine(
                    failure,
                    resources.rollbackConstruction(),
                    "Session resource construction and rollback both failed"));
            throw new AssertionError("unreachable");
        }
    }

    private SessionResources(
            Process process,
            SessionOutputMode outputMode,
            SessionOutputOwnership outputOwnership,
            ProcessIoResources resources,
            Runnable activity,
            Consumer<Throwable> terminalCloseFailures) {
        this.process = Objects.requireNonNull(process, "process");
        this.outputOwnership = Objects.requireNonNull(outputOwnership, "outputOwnership");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.terminalCloseFailures = Objects.requireNonNull(terminalCloseFailures, "terminalCloseFailures");
        this.stdin = new SessionStdin(resources.stdin().stream());
        this.stdoutClose = new CloseOnceInputStream(
                resources.stdout(), outputCloseReservation, OutputCloseReservation.Stream.STDOUT);
        this.stderrClose = new CloseOnceInputStream(
                resources.stderr(), outputCloseReservation, OutputCloseReservation.Stream.STDERR);
        this.stdout = new ActivityInputStream(stdoutClose, activity);
        this.stderr = new ActivityInputStream(stderrClose, activity);
        Objects.requireNonNull(outputMode, "outputMode");
    }

    OutputStream stdin() {
        return stdin;
    }

    InputStream publicStdout() {
        return outputOwnership.publicStream(stdout);
    }

    InputStream publicStderr() {
        return outputOwnership.publicStream(stderr);
    }

    InputStream ownedStdout(SessionOutputMode mode) {
        outputOwnership.ensureOwnedBy(mode);
        return stdout;
    }

    void claimHelperOutput(SessionOutputMode mode) {
        outputOwnership.claimHelper(mode);
    }

    void markHelperOutputReady(SessionOutputMode mode) {
        outputOwnership.markHelperReady(mode);
    }

    void requireHelperOutputReady(SessionOutputMode mode) {
        outputOwnership.requireHelperReady(mode);
    }

    InputStream ownedStderr(SessionOutputMode mode) {
        outputOwnership.ensureOwnedBy(mode);
        return stderr;
    }

    OutputCloseReservation.Reservation reserveOutputClose(
            SessionOutputMode mode, Consumer<OutputCloseReservation.Stream> pumpCloseObserver) {
        outputOwnership.ensureOwnedBy(mode);
        return outputCloseReservation.reserve(stdoutClose, stderrClose, pumpCloseObserver);
    }

    Throwable rollbackConstruction() {
        return resources.rollbackConstruction();
    }

    void closeStdin() {
        if (!stdinOpen.compareAndSet(true, false)) {
            return;
        }
        resources.stdin().closeRequiredAsync("procwright-process-stdin-close-", terminalCloseFailures, () -> {});
        activity.run();
    }

    void closeStdinForCleanup() {
        if (!stdinOpen.compareAndSet(true, false)) {
            return;
        }
        attemptBestEffort(this::closeStdinBestEffort);
        activity.run();
    }

    private void closeStdinAsync(Consumer<? super Throwable> failureHandler) {
        resources
                .stdin()
                .closeOwnedAsync(
                        "procwright-process-stdin-close-",
                        Objects.requireNonNull(failureHandler, "failureHandler"),
                        () -> {});
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stdinOpen.set(false);
        if (!resources.stdin().closeStarted()) {
            attemptBestEffort(this::closeStdinBestEffort);
        }
        if (outputOwnership.raw()) {
            attemptBestEffort(() -> resources
                    .stdout()
                    .closeOwnedAsync(
                            "procwright-process-stdout-close-", BoundedFailureReporter::reportBestEffort, () -> {}));
            attemptBestEffort(() -> resources
                    .stderr()
                    .closeOwnedAsync(
                            "procwright-process-stderr-close-", BoundedFailureReporter::reportBestEffort, () -> {}));
        }
    }

    private void closeStdinBestEffort() {
        closeStdinAsync(BoundedFailureReporter::reportBestEffort);
    }

    private static void attemptBestEffort(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            BoundedFailureReporter.reportBestEffort(failure);
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

    private final class SessionStdin extends OutputStream {

        private final OutputStream delegate;
        private final Object lock = new Object();

        private SessionStdin(OutputStream delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void write(int value) throws IOException {
            synchronized (lock) {
                ensureCanWrite();
                delegate.write(value);
                activity.run();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return;
            }
            synchronized (lock) {
                ensureCanWrite();
                delegate.write(bytes, offset, length);
                activity.run();
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (lock) {
                ensureCanWrite();
                delegate.flush();
            }
        }

        @Override
        public void close() {
            closeStdin();
        }

        private void ensureCanWrite() {
            if (!process.isAlive()) {
                throw new ProcessExitedException(exitCodeMessage());
            }
            if (!stdinOpen.get()) {
                throw new SessionStdinClosedException();
            }
        }

        private String exitCodeMessage() {
            try {
                return "Session process has exited with code " + process.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                return "Session process has exited";
            }
        }
    }
}
