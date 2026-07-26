/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.ProcessIoResources;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
    private final SessionOutputOwnership outputOwnership = new SessionOutputOwnership();
    private final SessionOutputCleanup outputCleanup;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean stdinOpen = new AtomicBoolean(true);
    private final Runnable activity;
    private final Consumer<CloseFailure> terminalCloseFailures;

    static SessionResources acquire(
            Process process,
            BoundedCloseDispatcher closeDispatcher,
            Runnable activity,
            Consumer<CloseFailure> terminalCloseFailures) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(closeDispatcher, "closeDispatcher");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(terminalCloseFailures, "terminalCloseFailures");
        SessionOutputCleanup outputCleanup = new SessionOutputCleanup();
        ProcessIoResources resources = ProcessIoResources.acquire(process, closeDispatcher, failure -> {
            outputCleanup.inlineFailed(failure);
            terminalCloseFailures.accept(CloseFailure.inlineOutput(failure));
        });
        try {
            return new SessionResources(process, resources, outputCleanup, activity, terminalCloseFailures);
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
            ProcessIoResources resources,
            SessionOutputCleanup outputCleanup,
            Runnable activity,
            Consumer<CloseFailure> terminalCloseFailures) {
        this.process = Objects.requireNonNull(process, "process");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.outputCleanup = Objects.requireNonNull(outputCleanup, "outputCleanup");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.terminalCloseFailures = Objects.requireNonNull(terminalCloseFailures, "terminalCloseFailures");
        this.stdin = new SessionStdin(resources.stdin().stream());
        this.stdoutClose = new CloseOnceInputStream(
                resources.stdout(), outputCloseReservation, OutputCloseReservation.Stream.STDOUT);
        this.stderrClose = new CloseOnceInputStream(
                resources.stderr(), outputCloseReservation, OutputCloseReservation.Stream.STDERR);
        this.stdout = new ActivityInputStream(stdoutClose, activity);
        this.stderr = new ActivityInputStream(stderrClose, activity);
        outputCleanup.bind(resources.stdout(), resources.stderr(), outputOwnership);
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

    void claimOutput(String owner) {
        outputOwnership.claim(owner);
    }

    InputStream ownedStdout(String owner) {
        outputOwnership.ensureOwnedBy(owner);
        return stdout;
    }

    InputStream ownedStderr(String owner) {
        outputOwnership.ensureOwnedBy(owner);
        return stderr;
    }

    OutputCloseReservation.Reservation reserveOutputClose(
            String owner, Consumer<OutputCloseReservation.Stream> pumpCloseObserver) {
        outputOwnership.ensureOwnedBy(owner);
        return outputCloseReservation.reserve(stdoutClose, stderrClose, pumpCloseObserver);
    }

    void dispatchUnreservedOutputClose(
            String owner,
            Consumer<? super Throwable> stdoutFailureHandler,
            Runnable stdoutCompletionHandler,
            Consumer<? super Throwable> stderrFailureHandler,
            Runnable stderrCompletionHandler) {
        outputOwnership.ensureOwnedBy(owner);
        io.github.ulviar.procwright.internal.ProcessStreamResource.closePairAsync(
                        resources.stdout(),
                        "procwright-helper-stdout-construction-rollback-",
                        stdoutFailureHandler,
                        stdoutCompletionHandler,
                        resources.stderr(),
                        "procwright-helper-stderr-construction-rollback-",
                        stderrFailureHandler,
                        stderrCompletionHandler)
                .rethrowStartFailure();
    }

    CompletableFuture<SessionOutputCleanup.Outcome> outputCleanupCompletion() {
        return outputCleanup.completion();
    }

    CompletableFuture<Void> physicalOutputView() {
        return outputCleanup.physicalView();
    }

    void afterPhysicalOutputCleanup(Runnable publication) {
        outputCleanup.afterSettlement(publication);
    }

    Throwable rollbackConstruction() {
        return resources.rollbackConstruction();
    }

    void closeStdin() {
        if (!stdinOpen.compareAndSet(true, false)) {
            return;
        }
        try {
            closeStdinAsync();
        } catch (RuntimeException | Error failure) {
            terminalCloseFailures.accept(CloseFailure.stdin(failure));
            throw failure;
        }
        activity.run();
    }

    void closeStdinAsync() {
        closeStdinAsync(failure -> terminalCloseFailures.accept(CloseFailure.stdin(failure)));
    }

    void closeStdinAsync(Consumer<? super Throwable> failureHandler) {
        resources
                .stdin()
                .closeOwnedAsync(
                        "procwright-process-stdin-close-",
                        Objects.requireNonNull(failureHandler, "failureHandler"),
                        () -> {})
                .rethrowStartFailure();
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stdinOpen.set(false);
        Throwable failure = null;
        if (!resources.stdin().closeStarted()) {
            try {
                closeStdinAsync();
            } catch (RuntimeException | Error closeFailure) {
                failure = closeFailure;
            }
        }
        if (outputOwnership.claimLifecycleClose()) {
            try {
                resources
                        .stdout()
                        .closeOwnedAsync("procwright-process-stdout-close-", ignored -> {}, () -> {})
                        .rethrowStartFailure();
            } catch (RuntimeException | Error closeFailure) {
                failure = FailureAggregation.combine(failure, closeFailure, "Multiple session stream closes failed");
            }
            try {
                resources
                        .stderr()
                        .closeOwnedAsync("procwright-process-stderr-close-", ignored -> {}, () -> {})
                        .rethrowStartFailure();
            } catch (RuntimeException | Error closeFailure) {
                failure = FailureAggregation.combine(failure, closeFailure, "Multiple session stream closes failed");
            }
        }
        rethrow(failure);
    }

    Throwable closeAfterFailure() {
        try {
            close();
            return null;
        } catch (RuntimeException | Error closeFailure) {
            return closeFailure;
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

    record CloseFailure(Throwable failure, boolean reportWhenLate) {

        CloseFailure {
            Objects.requireNonNull(failure, "failure");
        }

        private static CloseFailure stdin(Throwable failure) {
            return new CloseFailure(failure, true);
        }

        private static CloseFailure inlineOutput(Throwable failure) {
            return new CloseFailure(failure, false);
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
