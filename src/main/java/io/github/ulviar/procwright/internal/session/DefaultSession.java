/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Raw handle for an interactive command process.
 *
 * <p>A session exposes process streams directly and owns process lifecycle coordination. It does not serialize
 * line-oriented request/response workflows; higher-level scenarios should build those guarantees on top of this raw
 * handle.
 */
public final class DefaultSession implements Session {

    private final Process process;
    private final Charset charset;
    private final Duration idleTimeout;
    private final DiagnosticEmitter diagnostics;
    private final SessionResources resources;
    private final SessionTerminal terminal;
    private final SessionProcessCleanup processCleanup;
    private final SessionConstruction.Gate constructionGate;
    private final AtomicBoolean postOutcomeCleanupClaimed = new AtomicBoolean();
    private final AtomicLong lastActivityNanos;

    static DefaultSession openTransactionally(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            Runnable beforeCommit) {
        return constructTransactionally(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                SessionOutputMode.RAW,
                session -> {
                    beforeCommit.run();
                    return session;
                },
                (session, handle) -> {},
                BoundedCloseDispatcher.shared(),
                WatcherStarter.threading());
    }

    static <T> T openHelperTransactionally(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            SessionOutputMode outputMode,
            Function<? super DefaultSession, ? extends T> handleFactory) {
        return constructTransactionally(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                outputMode,
                handleFactory,
                (session, handle) -> session.requireHelperOutputReady(outputMode),
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
        return constructTransactionally(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                SessionOutputMode.RAW,
                session -> {
                    beforeCommit.run();
                    return session;
                },
                (session, handle) -> {},
                closeDispatcher,
                watcherStarter);
    }

    static <T> T openHelperTransactionally(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            SessionOutputMode outputMode,
            Function<? super DefaultSession, ? extends T> handleFactory,
            BoundedCloseDispatcher closeDispatcher,
            WatcherStarter watcherStarter) {
        return constructTransactionally(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                outputMode,
                handleFactory,
                (session, handle) -> session.requireHelperOutputReady(outputMode),
                closeDispatcher,
                watcherStarter);
    }

    static <T> T constructTransactionally(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            SessionOutputMode outputMode,
            Function<? super DefaultSession, ? extends T> handleFactory,
            BiConsumer<? super DefaultSession, ? super T> beforeCommit,
            BoundedCloseDispatcher closeDispatcher,
            WatcherStarter watcherStarter) {
        SessionConstruction construction = SessionConstruction.begin(process);
        try {
            DefaultSession session = new DefaultSession(
                    process,
                    idleTimeout,
                    shutdownPolicy,
                    charset,
                    diagnostics,
                    outputMode,
                    closeDispatcher,
                    construction,
                    watcherStarter);
            T handle = Objects.requireNonNull(
                    Objects.requireNonNull(handleFactory, "handleFactory").apply(session),
                    "handleFactory returned null");
            Objects.requireNonNull(beforeCommit, "beforeCommit").accept(session, handle);
            construction.commit();
            return handle;
        } catch (RuntimeException | Error failure) {
            throw SessionConstruction.unchecked(construction.rollback(failure));
        }
    }

    private DefaultSession(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            SessionOutputMode outputMode,
            BoundedCloseDispatcher closeDispatcher,
            SessionConstruction construction,
            WatcherStarter watcherStarter) {
        this.process = Objects.requireNonNull(process, "process");
        this.constructionGate = construction.gate();
        this.idleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(closeDispatcher, "closeDispatcher");
        this.terminal = new SessionTerminal(outputMode, diagnostics);
        this.processCleanup = new SessionProcessCleanup(process, shutdownPolicy);
        this.lastActivityNanos = new AtomicLong(System.nanoTime());

        this.resources = SessionResources.acquire(
                process,
                Objects.requireNonNull(outputMode, "outputMode"),
                closeDispatcher,
                this::markActivity,
                this::terminateAfterResourceCloseFailure);
        construction.own(resources);

        startExitWatcher(Objects.requireNonNull(watcherStarter, "watcherStarter"), constructionGate);
        startIdleWatcher(watcherStarter, constructionGate);
    }

    /**
     * Returns raw process stdout.
     *
     * @return stdout stream
     */
    public InputStream stdout() {
        return resources.publicStdout();
    }

    /**
     * Returns raw process stderr.
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
     * <p>After close work starts, this method returns even while another thread is blocked writing into a full stdin
     * pipe. The closed state is published first, so later writes fail with {@link IllegalStateException}, and the raw
     * stream close runs on a background thread. Infrastructure failure before that handoff may perform bounded terminal
     * cleanup before this method throws.
     */
    public void closeStdin() {
        resources.closeStdin();
    }

    /**
     * Returns an isolated process exit future view under the contract described by {@link Session#onExit()}.
     *
     * @return process exit future
     */
    public CompletableFuture<SessionExit> onExit() {
        return terminal.publicExit();
    }

    void observeExit(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        Objects.requireNonNull(observer, "observer");
        terminal.publicExit().whenComplete((result, failure) -> notifyObserver(observer, result, failure));
    }

    void observeTermination(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        Objects.requireNonNull(observer, "observer");
        terminal.observeProcess((result, failure) -> notifyObserver(observer, result, failure));
    }

    void observePrimaryOutcome(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        Objects.requireNonNull(observer, "observer");
        terminal.observePrimary((result, failure) -> notifyObserver(observer, result, failure));
    }

    void observePublicOutcome(Consumer<? super SessionTerminal.PublicOutcome> observer) {
        terminal.observePublic(observer);
    }

    boolean terminationPublished() {
        return terminal.processPublished();
    }

    boolean publicExitCompleted() {
        return terminal.publicExitCompleted();
    }

    OptionalInt processExitCode() {
        return processCleanup.exitCodeSnapshot();
    }

    /**
     * Requests process shutdown through the configured policy.
     *
     * <p>If another terminal action already owns shutdown, this method returns without joining its cleanup; use
     * {@link #onExit()} to await the logical terminal outcome. Calling this method more than once has no effect.
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

    private static void notifyObserver(
            BiConsumer<? super SessionExit, ? super Throwable> observer, SessionExit result, Throwable failure) {
        try {
            observer.accept(result, failure);
        } catch (Throwable observerFailure) {
            try {
                BoundedFailureReporter.shared().report(Thread.currentThread(), observerFailure);
            } catch (Throwable ignored) {
                // Reporting is best-effort and must not block or replace terminal publication.
            }
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
        SessionTerminal.ProcessClaim claim = terminal.claimFailure(failure);
        try {
            if (claim == null) {
                reportBestEffort(failure);
                if (!claimPostOutcomeCleanup()) {
                    return;
                }
            }
            retainOrReport(claim, processCleanup.forceAfterFailure());
            resources.close();
        } finally {
            if (claim != null) {
                claim.fail();
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
                    while (!terminal.processPublished()) {
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

    boolean closeFromHelper(boolean timedOut) {
        return closeFromHelper(timedOut, () -> {});
    }

    boolean closeFromHelper(boolean timedOut, Runnable afterClaim) {
        return stop(timedOut, timedOut ? "timeout" : "close", afterClaim);
    }

    private boolean stop(boolean timedOut) {
        return stop(timedOut, timedOut ? "idleTimeout" : "close", () -> {});
    }

    private boolean stop(boolean timedOut, String reason, Runnable afterClaim) {
        boolean restoreInterrupt = Thread.interrupted();
        SessionTerminal.ProcessClaim claim = terminal.claimClose(timedOut);
        Objects.requireNonNull(afterClaim, "afterClaim");
        boolean cleanupAttempted = false;

        try {
            if (claim == null) {
                if (!claimPostOutcomeCleanup()) {
                    return false;
                }
                resources.closeStdinForCleanup();
                processCleanup.stop();
                resources.close();
                return false;
            }
            afterClaim.run();
            resources.closeStdinForCleanup();
            diagnostics.emitBestEffort(
                    DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", reason));
            cleanupAttempted = true;
            OptionalInt exitCode = processCleanup.stop();
            resources.close();
            claim.succeed(new SessionExit(exitCode, timedOut));
            return true;
        } catch (RuntimeException | Error failure) {
            boolean interruptedDuringStop = Thread.interrupted();
            restoreInterrupt = restoreInterrupt || interruptedDuringStop;
            if (claim == null) {
                reportBestEffort(failure);
                resources.close();
                if (!timedOut || failure instanceof Error) {
                    throw failure;
                }
                return false;
            }
            claim.addFailure(failure);
            try {
                if (!cleanupAttempted) {
                    retainOrReport(claim, processCleanup.forceAfterFailure());
                }
                resources.close();
            } finally {
                claim.fail();
            }
            if (!timedOut || failure instanceof Error) {
                throw failure;
            }
            return true;
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void completeNaturalExit(int exitCode) {
        resources.closeStdinForCleanup();
        terminal.completeNaturalExit(new SessionExit(OptionalInt.of(exitCode), false));
    }

    private void markActivity() {
        lastActivityNanos.set(System.nanoTime());
    }

    void claimHelperOutput(SessionOutputMode mode) {
        resources.claimHelperOutput(mode);
    }

    void markHelperOutputReady(SessionOutputMode mode) {
        resources.markHelperOutputReady(mode);
    }

    void requireHelperOutputReady(SessionOutputMode mode) {
        resources.requireHelperOutputReady(mode);
    }

    void settleOutputMode(SessionTerminal.ModeSettlement settlement) {
        terminal.settleMode(settlement);
    }

    Runnable guardConstructionTask(Runnable task, Runnable completion) {
        return constructionGate.guard(task, completion);
    }

    InputStream ownedStdout(SessionOutputMode mode) {
        return resources.ownedStdout(mode);
    }

    InputStream ownedStderr(SessionOutputMode mode) {
        return resources.ownedStderr(mode);
    }

    OutputCloseReservation.Reservation reserveOwnedOutputClose(
            SessionOutputMode mode, Consumer<OutputCloseReservation.Stream> pumpCloseObserver) {
        return resources.reserveOutputClose(mode, pumpCloseObserver);
    }

    Charset charset() {
        return charset;
    }

    boolean terminateAfterHelperFailure(Throwable failure) {
        return terminateAfterHelperFailure(failure, () -> {});
    }

    boolean terminateAfterHelperFailure(Throwable failure, Runnable afterClaim) {
        return terminateAfterFailure(failure, afterClaim);
    }

    private void terminateAfterResourceCloseFailure(Throwable failure) {
        terminateAfterFailure(failure, () -> {});
    }

    private boolean terminateAfterFailure(Throwable failure, Runnable afterClaim) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(afterClaim, "afterClaim");
        boolean restoreInterrupt = Thread.interrupted();
        SessionTerminal.ProcessClaim claim = terminal.claimFailure(failure);
        try {
            if (claim == null) {
                reportBestEffort(failure);
                if (!claimPostOutcomeCleanup()) {
                    return false;
                }
            }
            if (claim != null) {
                try {
                    afterClaim.run();
                } catch (RuntimeException | Error actionFailure) {
                    claim.addFailure(actionFailure);
                }
                retainOrReport(claim, processCleanup.stopAfterFailure());
            } else {
                retainOrReport(null, processCleanup.forceAfterFailure());
            }
            resources.close();
        } finally {
            if (claim != null) {
                claim.fail();
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
        return claim != null;
    }

    private boolean claimPostOutcomeCleanup() {
        return !terminal.primaryClaimSelected() && postOutcomeCleanupClaimed.compareAndSet(false, true);
    }

    private static void retainOrReport(SessionTerminal.ProcessClaim claim, Throwable failure) {
        if (failure == null) {
            return;
        }
        if (claim == null) {
            reportBestEffort(failure);
        } else {
            claim.addFailure(failure);
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
