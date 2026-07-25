/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Raw handle for an interactive command process.
 *
 * <p>A session exposes process streams directly and owns process lifecycle coordination. It does not serialize
 * line-oriented request/response workflows; higher-level scenarios should build those guarantees on top of this raw
 * handle.
 */
public final class DefaultSession implements Session {

    private static final BoundedLifecyclePublisher EXIT_PUBLICATIONS =
            new BoundedLifecyclePublisher(BoundedCloseDispatcher.SHARED_MAX_OUTSTANDING_CAPACITY);

    private final Process process;
    private final Charset charset;
    private final Duration idleTimeout;
    private final DiagnosticEmitter diagnostics;
    private final SessionResources resources;
    private final SessionTermination termination;
    private final SessionExitBarrier exitBarrier;
    private final SessionProcessCleanup processCleanup;
    private final AtomicLong lastActivityNanos;

    public DefaultSession(Process process, Duration idleTimeout, ShutdownPolicy shutdownPolicy, Charset charset) {
        this(process, idleTimeout, shutdownPolicy, charset, defaultDiagnostics(process));
    }

    public DefaultSession(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics) {
        this(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                BoundedCloseDispatcher.shared(),
                BoundedLifecyclePublisher.shared(),
                EXIT_PUBLICATIONS,
                () -> {},
                WatcherStarter.threading());
    }

    static DefaultSession openTransactionally(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            Runnable beforeCommit) {
        return openTransactionally(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                beforeCommit,
                BoundedCloseDispatcher.shared(),
                WatcherStarter.threading());
    }

    static DefaultSession openTransactionally(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            Runnable beforeCommit,
            BoundedCloseDispatcher closeDispatcher,
            WatcherStarter watcherStarter) {
        return new DefaultSession(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                closeDispatcher,
                BoundedLifecyclePublisher.shared(),
                EXIT_PUBLICATIONS,
                beforeCommit,
                watcherStarter);
    }

    private DefaultSession(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            BoundedCloseDispatcher closeDispatcher,
            BoundedLifecyclePublisher resourcePublisher,
            BoundedLifecyclePublisher exitPublisher,
            Runnable beforeCommit,
            WatcherStarter watcherStarter) {
        this.process = Objects.requireNonNull(process, "process");
        SessionConstruction construction = SessionConstruction.begin(process);
        SessionConstruction.Gate gate = construction.gate();
        try {
            this.idleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
            Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            this.charset = Objects.requireNonNull(charset, "charset");
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(closeDispatcher, "closeDispatcher");
            Objects.requireNonNull(resourcePublisher, "resourcePublisher");
            Objects.requireNonNull(exitPublisher, "exitPublisher");
            this.termination = new SessionTermination(diagnostics);
            this.processCleanup = new SessionProcessCleanup(process, shutdownPolicy);
            this.lastActivityNanos = new AtomicLong(System.nanoTime());

            this.resources = SessionResources.acquire(
                    process,
                    closeDispatcher,
                    resourcePublisher,
                    this::markActivity,
                    this::terminateAfterResourceCloseFailure);
            construction.own(resources);

            BoundedLifecyclePublisher.Reservation publicationReservation = exitPublisher.reserve(1);
            construction.own(publicationReservation);
            BoundedLifecyclePublisher.Permit exitPublication = publicationReservation.takePermit();
            construction.own(exitPublication);
            this.exitBarrier = new SessionExitBarrier(exitPublication);
            observePublicExitCleanup();

            startExitWatcher(Objects.requireNonNull(watcherStarter, "watcherStarter"), gate);
            startIdleWatcher(watcherStarter, gate);
            Objects.requireNonNull(beforeCommit, "beforeCommit").run();
            construction.commit();
        } catch (RuntimeException | Error failure) {
            throw SessionConstruction.unchecked(construction.rollback(failure));
        }
    }

    private static DiagnosticEmitter defaultDiagnostics(Process process) {
        Objects.requireNonNull(process, "process");
        try {
            return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session", CommandEcho.empty());
        } catch (RuntimeException | Error failure) {
            throw SessionConstruction.unchecked(SessionConstruction.rollbackUnowned(process, failure));
        }
    }

    /**
     * Returns raw process stdout.
     *
     * <p>The returned stream is usable only while no higher-level Procwright helper owns this session output. The first
     * consuming or lifecycle operation on a public stdout or stderr stream selects raw public-stream mode for this
     * session. After {@link Expect}, {@link LineSession}, {@link ProtocolSession}, or {@link StreamSession} claims
     * output ownership, public stream consuming and lifecycle operations fail with {@link IllegalStateException}.
     * Closing an already obtained wrapper after the session lifecycle has closed stdout is harmless.
     *
     * @return stdout stream
     */
    public InputStream stdout() {
        return resources.publicStdout();
    }

    /**
     * Returns raw process stderr.
     *
     * <p>The returned stream is usable only while no higher-level Procwright helper owns this session output. The first
     * consuming or lifecycle operation on a public stdout or stderr stream selects raw public-stream mode for this
     * session. After {@link Expect}, {@link LineSession}, {@link ProtocolSession}, or {@link StreamSession} claims
     * output ownership, public stream consuming and lifecycle operations fail with {@link IllegalStateException}.
     * Closing an already obtained wrapper after the session lifecycle has closed stderr is harmless.
     *
     * @return stderr stream
     */
    public InputStream stderr() {
        return resources.publicStderr();
    }

    /**
     * Returns raw process stdin guarded by the session lifecycle state.
     *
     * @return stdin stream
     */
    public OutputStream stdin() {
        return resources.stdin();
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
     *
     * <p>This method returns promptly even while another thread is blocked writing into a full stdin pipe. The closed
     * state is published first, so later writes fail with {@link IllegalStateException}, and the raw stream close runs
     * on a background thread: closing the raw stream synchronously would block on the stream monitor held by the
     * blocked writer until the child drains the pipe or exits.
     */
    public void closeStdin() {
        resources.closeStdin();
    }

    /**
     * Returns an isolated process exit future view after the full cleanup barrier described by
     * {@link Session#onExit()}.
     *
     * @return process exit future
     */
    public CompletableFuture<SessionExit> onExit() {
        return exitBarrier.view();
    }

    void observeExit(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        Objects.requireNonNull(observer, "observer");
        termination.observe((result, failure) -> {
            try {
                observer.accept(result, failure);
            } catch (Throwable observerFailure) {
                try {
                    BoundedFailureReporter.shared().report(Thread.currentThread(), observerFailure);
                } catch (Throwable ignored) {
                    // Reporting is best-effort and must not block or replace terminal publication.
                }
            }
        });
    }

    boolean terminationPublished() {
        return termination.published();
    }

    boolean publicExitCompleted() {
        return exitBarrier.completed();
    }

    CompletableFuture<Void> physicalOutputCleanup() {
        return resources.physicalOutputView();
    }

    OptionalInt processExitCode() {
        return processCleanup.exitCodeSnapshot();
    }

    void afterPhysicalOutputCleanup(Runnable publication) {
        resources.afterPhysicalOutputCleanup(publication);
    }

    /**
     * Creates an expect automation helper using default options.
     *
     * @return expect helper
     */
    public Expect.Draft expect() {
        return Session.super.expect();
    }

    /**
     * Stops the process through the configured shutdown policy. Calling this method more than once has no effect.
     */
    @Override
    public void close() {
        stop(false);
    }

    private void sendBytes(byte[] bytes) {
        try {
            resources.stdin().write(bytes);
            resources.stdin().flush();
        } catch (IOException exception) {
            throw new CommandExecutionException("Could not write session stdin", exception);
        }
    }

    private void startExitWatcher(WatcherStarter watcherStarter, SessionConstruction.Gate gate) {
        Objects.requireNonNull(
                watcherStarter.start("procwright-session-exit-", gate.guard(() -> {
                    try {
                        int exitCode = processCleanup.awaitNaturalExit();
                        completeNaturalExit(exitCode);
                    } catch (InterruptedException exception) {
                        completeWatcherFailure(new CommandExecutionException(
                                "Interrupted while waiting for session completion", exception));
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException | Error failure) {
                        completeWatcherFailure(failure);
                        if (failure instanceof Error error) {
                            throw error;
                        }
                    }
                })),
                "watcher starter returned null");
    }

    private void completeWatcherFailure(Throwable failure) {
        boolean restoreInterrupt = Thread.interrupted();
        SessionTermination.FailureClaim failureClaim = termination.claimFailure(failure);
        try {
            retainOrReport(failureClaim, processCleanup.forceAfterFailure());
            retainOrReport(failureClaim, resources.closeAfterFailure());
        } finally {
            if (failureClaim != null) {
                failureClaim.finishCleanup();
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startIdleWatcher(WatcherStarter watcherStarter, SessionConstruction.Gate gate) {
        if (idleTimeout.isZero()) {
            return;
        }

        long idleTimeoutNanos = DurationSupport.saturatedNanos(idleTimeout);
        Objects.requireNonNull(
                watcherStarter.start("procwright-session-idle-timeout-", gate.guard(() -> {
                    while (!termination.published()) {
                        long elapsedNanos = System.nanoTime() - lastActivityNanos.get();
                        long remainingNanos = idleTimeoutNanos - elapsedNanos;
                        if (remainingNanos <= 0) {
                            stop(true);
                            return;
                        }
                        if (!sleepNanos(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100)))) {
                            return;
                        }
                    }
                })),
                "watcher starter returned null");
    }

    private void stop(boolean timedOut) {
        boolean restoreInterrupt = Thread.interrupted();

        try {
            if (!termination.beginClosing()) {
                if (termination.closedAndPublished()) {
                    resources.closeStdin();
                    processCleanup.stop();
                    resources.close();
                }
                return;
            }
            resources.closeStdin();
            diagnostics.emit(
                    DiagnosticEventType.SHUTDOWN_REQUESTED,
                    DiagnosticEmitter.attributes("reason", timedOut ? "idleTimeout" : "close"));
            OptionalInt exitCode = processCleanup.stop();
            resources.close();
            SessionTermination.Publication publication = termination.claimCloseSuccess();
            if (publication != null) {
                publication.publishSuccess(new SessionExit(exitCode, timedOut));
            }
        } catch (RuntimeException | Error failure) {
            boolean interruptedDuringStop = Thread.interrupted();
            restoreInterrupt = restoreInterrupt || interruptedDuringStop;
            SessionTermination.FailureClaim failureClaim = termination.claimFailure(failure);
            try {
                retainOrReport(failureClaim, processCleanup.forceAfterFailure());
                retainOrReport(failureClaim, resources.closeAfterFailure());
            } finally {
                if (failureClaim != null) {
                    failureClaim.finishCleanup();
                }
            }
            if (!timedOut || failure instanceof Error) {
                throw failure;
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void completeNaturalExit(int exitCode) {
        SessionTermination.Publication publication = termination.claimNaturalSuccess();
        if (publication == null) {
            return;
        }
        try {
            resources.close();
        } catch (RuntimeException | Error failure) {
            publication.recordFailure(failure);
            retain(publication, processCleanup.forceAfterFailure());
            retain(publication, resources.closeAfterFailure());
            publication.publishFailure();
            throw failure;
        }
        publication.publishSuccess(new SessionExit(OptionalInt.of(exitCode), false));
    }

    private void observePublicExitCleanup() {
        exitBarrier.observe(termination.outcome(), resources.outputCleanupCompletion());
    }

    private void markActivity() {
        lastActivityNanos.set(System.nanoTime());
    }

    void claimOutputOwner(String owner) {
        termination.ensureOpenForOutputClaim();
        resources.claimOutput(owner);
    }

    SessionExitBarrier.Registration registerHelperCleanup() {
        return exitBarrier.registerHelper();
    }

    InputStream ownedStdout(String owner) {
        return resources.ownedStdout(owner);
    }

    InputStream ownedStderr(String owner) {
        return resources.ownedStderr(owner);
    }

    OutputCloseReservation.Reservation reserveOwnedOutputClose(String owner, Runnable pumpCloseObserver) {
        return resources.reserveOutputClose(owner, pumpCloseObserver);
    }

    void dispatchUnreservedOwnedOutputClose(
            String owner,
            Consumer<? super Throwable> stdoutFailureHandler,
            Runnable stdoutCompletionHandler,
            Consumer<? super Throwable> stderrFailureHandler,
            Runnable stderrCompletionHandler) {
        resources.dispatchUnreservedOutputClose(
                owner, stdoutFailureHandler, stdoutCompletionHandler, stderrFailureHandler, stderrCompletionHandler);
    }

    Charset charset() {
        return charset;
    }

    private void terminateAfterResourceCloseFailure(SessionResources.CloseFailure resourceFailure) {
        Throwable failure = resourceFailure.failure();
        boolean restoreInterrupt = Thread.interrupted();
        SessionTermination.FailureClaim failureClaim = termination.claimFailure(failure);
        try {
            if (failureClaim != null && failureClaim.ownsPublication()) {
                retainOrReport(failureClaim, processCleanup.stopAfterFailure());
            } else {
                retainOrReport(failureClaim, processCleanup.forceAfterFailure());
            }
            retainOrReport(failureClaim, resources.closeAfterFailure());
            if (failureClaim == null && resourceFailure.reportWhenLate()) {
                reportBestEffort(failure);
            }
        } finally {
            if (failureClaim != null) {
                failureClaim.finishCleanup();
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void retainOrReport(SessionTermination.FailureClaim claim, Throwable failure) {
        if (failure == null) {
            return;
        }
        if (claim == null) {
            reportBestEffort(failure);
        } else {
            claim.recordFailure(failure);
        }
    }

    private static void retain(SessionTermination.Publication publication, Throwable failure) {
        if (failure != null) {
            publication.recordFailure(failure);
        }
    }

    private static void reportBestEffort(Throwable failure) {
        try {
            BoundedFailureReporter.FailureTarget target = BoundedFailureReporter.captureFailureTarget();
            BoundedFailureReporter.shared().report(target, failure);
        } catch (RuntimeException | Error ignored) {
            // Mandatory cleanup and publication never depend on late reporting.
        }
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }

    private static boolean sleepNanos(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(Math.max(1, nanos));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    interface WatcherStarter {

        Thread start(String threadPrefix, Runnable task);

        static WatcherStarter threading() {
            return Threading::start;
        }
    }
}
