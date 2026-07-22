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
import io.github.ulviar.procwright.internal.ProcessIoResources;
import io.github.ulviar.procwright.internal.ProcessLifecycle;
import io.github.ulviar.procwright.internal.SuppressionSupport;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
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

    private static final Duration CONSTRUCTION_FAILURE_CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private static final BoundedLifecyclePublisher EXIT_PUBLICATIONS =
            new BoundedLifecyclePublisher(BoundedCloseDispatcher.SHARED_MAX_OUTSTANDING_CAPACITY);
    private static final ConstructionProbe NO_CONSTRUCTION_FAILURES = point -> {};
    private static final ConstructionRollback DEFAULT_CONSTRUCTION_ROLLBACK = new ConstructionRollback() {};

    private final Process process;
    private final ShutdownPolicy shutdownPolicy;
    private final Charset charset;
    private final Duration idleTimeout;
    private final DiagnosticEmitter diagnostics;
    private final ProcessIoResources ioResources;
    private final SessionStdin stdin;
    private final OutputCloseReservation outputCloseReservation;
    private final CloseOnceInputStream stdoutClose;
    private final CloseOnceInputStream stderrClose;
    private final InputStream stdout;
    private final InputStream stderr;
    private final ProcessExitObservation processExitObservation;
    private final CompletableFuture<SessionExit> processTerminal;
    private final CompletableFuture<SessionExit> exit;
    private final BoundedLifecyclePublisher.Permit exitPublication;
    private final CompletableFuture<Void> physicalOutputCleanup;
    private final AtomicBoolean resourcesClosed;
    private final Set<Throwable> reportedLateResourceCloseFailures;
    private final AtomicReference<State> state;
    private final AtomicReference<Throwable> terminalFailure;
    private final AtomicReference<Set<ProcessHandle>> liveDescendants;
    private final AtomicLong lastActivityNanos;
    private final SessionOutputOwnership outputOwnership;
    private final Object stdinLock;
    private final Object processTreeLock;
    private final TerminalArbiter terminalArbiter;
    private final PublicExitBarrier publicExitBarrier;

    private boolean processTreeCleanupCompleted;
    private OptionalInt processTreeExitCode = OptionalInt.empty();

    public DefaultSession(Process process, Duration idleTimeout, ShutdownPolicy shutdownPolicy, Charset charset) {
        this(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session", CommandEcho.empty()));
    }

    public DefaultSession(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics) {
        this(
                ConstructionInputs.prepare(
                        process,
                        idleTimeout,
                        shutdownPolicy,
                        charset,
                        diagnostics,
                        BoundedCloseDispatcher.shared(),
                        BoundedLifecyclePublisher.shared(),
                        EXIT_PUBLICATIONS,
                        NO_CONSTRUCTION_FAILURES,
                        DEFAULT_CONSTRUCTION_ROLLBACK),
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
                ConstructionInputs.prepare(
                        process,
                        idleTimeout,
                        shutdownPolicy,
                        charset,
                        diagnostics,
                        closeDispatcher,
                        BoundedLifecyclePublisher.shared(),
                        EXIT_PUBLICATIONS,
                        NO_CONSTRUCTION_FAILURES,
                        DEFAULT_CONSTRUCTION_ROLLBACK),
                beforeCommit,
                watcherStarter);
    }

    static DefaultSession openTransactionally(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            Runnable beforeCommit,
            BoundedCloseDispatcher closeDispatcher,
            BoundedLifecyclePublisher resourcePublisher,
            BoundedLifecyclePublisher exitPublisher,
            WatcherStarter watcherStarter,
            ConstructionProbe constructionProbe) {
        return openTransactionally(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                beforeCommit,
                closeDispatcher,
                resourcePublisher,
                exitPublisher,
                watcherStarter,
                constructionProbe,
                DEFAULT_CONSTRUCTION_ROLLBACK);
    }

    static DefaultSession openTransactionally(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            Runnable beforeCommit,
            BoundedCloseDispatcher closeDispatcher,
            BoundedLifecyclePublisher resourcePublisher,
            BoundedLifecyclePublisher exitPublisher,
            WatcherStarter watcherStarter,
            ConstructionProbe constructionProbe,
            ConstructionRollback constructionRollback) {
        return new DefaultSession(
                ConstructionInputs.prepare(
                        process,
                        idleTimeout,
                        shutdownPolicy,
                        charset,
                        diagnostics,
                        closeDispatcher,
                        resourcePublisher,
                        exitPublisher,
                        constructionProbe,
                        constructionRollback),
                beforeCommit,
                watcherStarter);
    }

    private DefaultSession(ConstructionInputs inputs, Runnable beforeCommit, WatcherStarter watcherStarter) {
        this.process = inputs.process();
        this.idleTimeout = inputs.idleTimeout();
        this.shutdownPolicy = inputs.shutdownPolicy();
        this.charset = inputs.charset();
        this.diagnostics = inputs.diagnostics();
        ConstructionProbe probe = inputs.constructionProbe();
        ConstructionLedger ledger;
        try {
            ledger = new ConstructionLedger(process, inputs.constructionRollback());
        } catch (RuntimeException | Error failure) {
            cleanupProcessPreserving(process, failure, inputs.constructionRollback());
            throw failure;
        }
        try {
            probe.at(ConstructionPoint.BEFORE_PROCESS_EXIT_OBSERVATION_CONSTRUCTION);
            this.processExitObservation = new ProcessExitObservation();
            probe.at(ConstructionPoint.BEFORE_PROCESS_TERMINAL_CONSTRUCTION);
            this.processTerminal = new CompletableFuture<>();
            probe.at(ConstructionPoint.BEFORE_PUBLIC_EXIT_CONSTRUCTION);
            this.exit = new CompletableFuture<>();
            probe.at(ConstructionPoint.BEFORE_RESOURCES_CLOSED_CONSTRUCTION);
            this.resourcesClosed = new AtomicBoolean();
            probe.at(ConstructionPoint.BEFORE_LATE_FAILURE_SET_CONSTRUCTION);
            this.reportedLateResourceCloseFailures = Collections.newSetFromMap(new IdentityHashMap<>());
            probe.at(ConstructionPoint.BEFORE_STATE_CONSTRUCTION);
            this.state = new AtomicReference<>(State.RUNNING);
            probe.at(ConstructionPoint.BEFORE_TERMINAL_FAILURE_CONSTRUCTION);
            this.terminalFailure = new AtomicReference<>();
            probe.at(ConstructionPoint.BEFORE_DESCENDANTS_CONSTRUCTION);
            this.liveDescendants = new AtomicReference<>();
            probe.at(ConstructionPoint.BEFORE_ACTIVITY_COUNTER_CONSTRUCTION);
            this.lastActivityNanos = new AtomicLong(System.nanoTime());
            probe.at(ConstructionPoint.BEFORE_OUTPUT_OWNERSHIP_CONSTRUCTION);
            this.outputOwnership = new SessionOutputOwnership();
            probe.at(ConstructionPoint.BEFORE_STDIN_LOCK_CONSTRUCTION);
            this.stdinLock = new Object();
            probe.at(ConstructionPoint.BEFORE_PROCESS_TREE_LOCK_CONSTRUCTION);
            this.processTreeLock = new Object();
            probe.at(ConstructionPoint.BEFORE_TERMINAL_ARBITER_CONSTRUCTION);
            this.terminalArbiter = new TerminalArbiter();

            probe.at(ConstructionPoint.BEFORE_PROCESS_IO_ACQUISITION);
            this.ioResources = ProcessIoResources.acquire(
                    process,
                    inputs.closeDispatcher(),
                    inputs.resourcePublisher(),
                    this::terminateAfterInlineOutputCloseFailure);
            ledger.own(ioResources);
            probe.at(ConstructionPoint.AFTER_PROCESS_IO_ACQUISITION);

            probe.at(ConstructionPoint.BEFORE_STDIN_WRAPPER_CONSTRUCTION);
            this.stdin = new SessionStdin(ioResources.stdin().stream());
            probe.at(ConstructionPoint.BEFORE_OUTPUT_CLOSE_RESERVATION_CONSTRUCTION);
            this.outputCloseReservation = new OutputCloseReservation();
            probe.at(ConstructionPoint.BEFORE_STDOUT_CLOSE_WRAPPER_CONSTRUCTION);
            this.stdoutClose = new CloseOnceInputStream(
                    ioResources.stdout(), outputCloseReservation, OutputCloseReservation.Stream.STDOUT);
            probe.at(ConstructionPoint.BEFORE_STDERR_CLOSE_WRAPPER_CONSTRUCTION);
            this.stderrClose = new CloseOnceInputStream(
                    ioResources.stderr(), outputCloseReservation, OutputCloseReservation.Stream.STDERR);
            probe.at(ConstructionPoint.BEFORE_STDOUT_ACTIVITY_WRAPPER_CONSTRUCTION);
            this.stdout = new ActivityInputStream(stdoutClose, this::markActivity);
            probe.at(ConstructionPoint.BEFORE_STDERR_ACTIVITY_WRAPPER_CONSTRUCTION);
            this.stderr = new ActivityInputStream(stderrClose, this::markActivity);
            probe.at(ConstructionPoint.BEFORE_PHYSICAL_OUTPUT_CLEANUP_CONSTRUCTION);
            this.physicalOutputCleanup = physicalOutputCleanup(ioResources.stdout(), ioResources.stderr());
            probe.at(ConstructionPoint.BEFORE_PUBLIC_EXIT_BARRIER_CONSTRUCTION);
            this.publicExitBarrier = new PublicExitBarrier();

            probe.at(ConstructionPoint.BEFORE_EXIT_PUBLICATION_RESERVATION);
            BoundedLifecyclePublisher.Reservation publicationReservation =
                    inputs.exitPublisher().reserve(1);
            ledger.own(publicationReservation);
            this.exitPublication = publicationReservation.takePermit();
            ledger.own(exitPublication);
            probe.at(ConstructionPoint.AFTER_EXIT_PUBLICATION_TRANSFER);
            probe.at(ConstructionPoint.BEFORE_CLEANUP_OBSERVER_REGISTRATION);
            observePublicExitCleanup();

            probe.at(ConstructionPoint.BEFORE_CONSTRUCTION_GATE_CONSTRUCTION);
            ConstructionGate gate = new ConstructionGate();
            ledger.own(gate);
            probe.at(ConstructionPoint.BEFORE_EXIT_WATCHER_START);
            startExitWatcher(Objects.requireNonNull(watcherStarter, "watcherStarter"), gate);
            probe.at(ConstructionPoint.AFTER_EXIT_WATCHER_START);
            probe.at(ConstructionPoint.BEFORE_IDLE_WATCHER_START);
            startIdleWatcher(watcherStarter, gate);
            probe.at(ConstructionPoint.AFTER_IDLE_WATCHER_START);
            Objects.requireNonNull(beforeCommit, "beforeCommit").run();
            ledger.commit();
            gate.commit();
        } catch (RuntimeException | Error failure) {
            ledger.rollback(failure);
            throw failure;
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
        return outputOwnership.publicStream(stdout);
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
     *
     * <p>This method returns promptly even while another thread is blocked writing into a full stdin pipe. The closed
     * state is published first, so later writes fail with {@link IllegalStateException}, and the raw stream close runs
     * on a background thread: closing the raw stream synchronously would block on the stream monitor held by the
     * blocked writer until the child drains the pipe or exits.
     */
    public void closeStdin() {
        if (state.compareAndSet(State.RUNNING, State.STDIN_CLOSED)) {
            try {
                closeRawStdinAsync(this::terminateAfterResourceCloseFailure);
            } catch (RuntimeException | Error failure) {
                if (!processTerminal.isDone()) {
                    terminateAfterResourceCloseFailure(failure);
                }
                throw failure;
            }
            markActivity();
        }
    }

    /**
     * Returns an isolated process exit future view after the full cleanup barrier described by
     * {@link Session#onExit()}.
     *
     * @return process exit future
     */
    public CompletableFuture<SessionExit> onExit() {
        return exit.copy();
    }

    void observeExit(BiConsumer<? super SessionExit, ? super Throwable> observer) {
        Objects.requireNonNull(observer, "observer");
        processTerminal.whenComplete((result, failure) -> {
            try {
                observer.accept(result, failure);
            } catch (Throwable observerFailure) {
                Threading.reportUncaught(Thread.currentThread(), observerFailure);
            }
        });
    }

    boolean exitCompleted() {
        return processTerminal.isDone();
    }

    int exitDependentCount() {
        return processTerminal.getNumberOfDependents();
    }

    CompletableFuture<Void> physicalOutputCleanup() {
        return physicalOutputCleanup.copy();
    }

    ProcessExitObservation processExitObservation() {
        return processExitObservation;
    }

    void awaitPhysicalOutputPublication() {
        boolean restoreInterrupt = false;
        while (!physicalOutputCleanup.isDone()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            if (Thread.interrupted()) {
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
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
            stdin.write(bytes);
            stdin.flush();
        } catch (IOException exception) {
            throw new CommandExecutionException("Could not write session stdin", exception);
        }
    }

    private void startExitWatcher(WatcherStarter watcherStarter, ConstructionGate gate) {
        Objects.requireNonNull(
                watcherStarter.start("procwright-session-exit-", gate.guard(() -> {
                    try {
                        ProcessLifecycle.waitFor(process, Duration.ZERO, liveDescendants);
                        int exitCode = process.exitValue();
                        processExitObservation.publish(exitCode);
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
        boolean restoreInterrupt = Thread.interrupted() || SuppressionSupport.containsInterruption(failure);
        TerminalPublication publication = terminalArbiter.claimFailure(failure);
        try {
            forceCleanupPreserving(failure);
            closeResourcesPreserving(failure);
            if (publication != null) {
                publication.publishFailure(failure);
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startIdleWatcher(WatcherStarter watcherStarter, ConstructionGate gate) {
        if (idleTimeout.isZero()) {
            return;
        }

        long idleTimeoutNanos = DurationSupport.saturatedNanos(idleTimeout);
        Objects.requireNonNull(
                watcherStarter.start("procwright-session-idle-timeout-", gate.guard(() -> {
                    while (!processTerminal.isDone()) {
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
            if (!transitionToClosing()) {
                if (state.get() == State.CLOSED && processTerminal.isDone()) {
                    closeRawStdinAsync();
                    stopProcessTree();
                    closeResources();
                }
                return;
            }
            closeRawStdinAsync();
            diagnostics.emit(
                    DiagnosticEventType.SHUTDOWN_REQUESTED,
                    DiagnosticEmitter.attributes("reason", timedOut ? "idleTimeout" : "close"));
            OptionalInt exitCode = stopProcessTree();
            closeResources();
            TerminalPublication publication = terminalArbiter.claimCloseSuccess();
            if (publication != null) {
                publication.publishSuccess(new SessionExit(exitCode, timedOut));
            }
        } catch (RuntimeException | Error failure) {
            boolean interruptedDuringStop = Thread.interrupted();
            restoreInterrupt =
                    restoreInterrupt || SuppressionSupport.containsInterruption(failure) || interruptedDuringStop;
            TerminalPublication publication = terminalArbiter.claimFailure(failure);
            forceCleanupPreserving(failure);
            closeResourcesPreserving(failure);
            if (publication != null) {
                publication.publishFailure(failure);
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

    private OptionalInt stopProcessTree() {
        synchronized (processTreeLock) {
            if (processTreeCleanupCompleted) {
                return processTreeExitCode;
            }
            try {
                processTreeExitCode =
                        ProcessLifecycle.stopWithoutStdinClose(process, knownDescendants(), shutdownPolicy);
                return processTreeExitCode;
            } finally {
                processTreeCleanupCompleted = true;
            }
        }
    }

    private void forceCleanupPreserving(Throwable primaryFailure) {
        synchronized (processTreeLock) {
            if (processTreeCleanupCompleted) {
                return;
            }
            try {
                ProcessLifecycle.forceStopWithoutStdinClose(process, knownDescendants(), Duration.ofSeconds(5));
            } catch (RuntimeException | Error cleanupFailure) {
                SuppressionSupport.attach(primaryFailure, cleanupFailure);
            } finally {
                processTreeCleanupCompleted = true;
            }
        }
    }

    private void stopProcessTreePreserving(Throwable primaryFailure) {
        synchronized (processTreeLock) {
            if (processTreeCleanupCompleted) {
                return;
            }
            try {
                processTreeExitCode =
                        ProcessLifecycle.stopWithoutStdinClose(process, knownDescendants(), shutdownPolicy);
            } catch (RuntimeException | Error cleanupFailure) {
                SuppressionSupport.attach(primaryFailure, cleanupFailure);
            } finally {
                processTreeCleanupCompleted = true;
            }
        }
    }

    private Set<ProcessHandle> knownDescendants() {
        Set<ProcessHandle> snapshot = liveDescendants.get();
        return snapshot == null ? Set.of() : snapshot;
    }

    private void emitPreserving(
            DiagnosticEventType type, java.util.Map<String, String> attributes, Throwable primaryFailure) {
        try {
            diagnostics.emit(type, attributes);
        } catch (RuntimeException | Error diagnosticFailure) {
            SuppressionSupport.attach(primaryFailure, diagnosticFailure);
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

    private void completeNaturalExit(int exitCode) {
        TerminalPublication publication = terminalArbiter.claimNaturalSuccess();
        if (publication == null) {
            return;
        }
        try {
            closeResources();
        } catch (RuntimeException | Error failure) {
            terminalFailure.compareAndSet(null, failure);
            forceCleanupPreserving(failure);
            closeResourcesPreserving(failure);
            publication.publishFailure(failure);
            throw failure;
        }
        publication.publishSuccess(new SessionExit(OptionalInt.of(exitCode), false));
    }

    private void observePublicExitCleanup() {
        processTerminal.whenComplete(publicExitBarrier::processCompleted);
        physicalOutputCleanup.whenComplete((ignored, failure) -> publicExitBarrier.physicalOutputCompleted());
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

    HelperCleanupRegistration registerHelperCleanup() {
        return publicExitBarrier.registerHelper();
    }

    InputStream ownedStdout(String owner) {
        ensureOutputOwnedBy(owner);
        return stdout;
    }

    InputStream ownedStderr(String owner) {
        ensureOutputOwnedBy(owner);
        return stderr;
    }

    OutputCloseReservation.Reservation reserveOwnedOutputClose(
            String owner, Consumer<OutputCloseReservation.Stream> pumpCloseObserver) {
        ensureOutputOwnedBy(owner);
        return outputCloseReservation.reserve(stdoutClose, stderrClose, pumpCloseObserver);
    }

    void dispatchUnreservedOwnedOutputClose(
            String owner,
            Consumer<? super Throwable> stdoutFailureHandler,
            Runnable stdoutCompletionHandler,
            Consumer<? super Throwable> stderrFailureHandler,
            Runnable stderrCompletionHandler) {
        ensureOutputOwnedBy(owner);
        ProcessIoResources.closePairAsync(
                ioResources.stdout(),
                "procwright-helper-stdout-construction-rollback-",
                stdoutFailureHandler,
                stdoutCompletionHandler,
                ioResources.stderr(),
                "procwright-helper-stderr-construction-rollback-",
                stderrFailureHandler,
                stderrCompletionHandler);
    }

    long pid() {
        return process.pid();
    }

    Charset charset() {
        return charset;
    }

    private static java.util.Map<String, String> exitAttributes(OptionalInt exitCode, boolean timedOut) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("timedOut", Boolean.toString(timedOut));
        exitCode.ifPresent(value -> attributes.put("exitCode", Integer.toString(value)));
        return attributes;
    }

    private void ensureCanWrite() {
        if (!process.isAlive()) {
            throw new ProcessExitedException(exitCodeMessage());
        }
        ensureStdinOpen();
    }

    private String exitCodeMessage() {
        try {
            return "Session process has exited with code " + process.exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return "Session process has exited";
        }
    }

    private void ensureStdinOpen() {
        if (state.get() != State.RUNNING) {
            throw new SessionStdinClosedException();
        }
    }

    private void ensureOutputOwnedBy(String owner) {
        outputOwnership.ensureOwnedBy(owner);
    }

    private void closeRawStdinAsync() {
        closeRawStdinAsync(this::terminateAfterResourceCloseFailure);
    }

    private void closeRawStdinAsync(Consumer<? super Throwable> failureHandler) {
        ioResources
                .stdin()
                .closeOwnedAsync(
                        "procwright-process-stdin-close-",
                        Objects.requireNonNull(failureHandler, "failureHandler"),
                        () -> {});
    }

    private void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = null;
        if (!ioResources.stdin().closeStarted()) {
            failure = captureCleanupFailure(failure, this::closeRawStdinAsync);
        }
        // A higher-level output helper drains and closes both streams from its pump threads.
        if (outputOwnership.claimLifecycleClose()) {
            failure = captureCleanupFailure(
                    failure,
                    () -> stdoutClose.dispatchLifecycleClose(
                            "procwright-process-stdout-close-", this::terminateAfterResourceCloseFailure, () -> {}));
            failure = captureCleanupFailure(
                    failure,
                    () -> stderrClose.dispatchLifecycleClose(
                            "procwright-process-stderr-close-", this::terminateAfterResourceCloseFailure, () -> {}));
        }
        rethrowCleanupFailure(failure);
    }

    private void terminateAfterResourceCloseFailure(Throwable failure) {
        terminateAfterResourceCloseFailure(failure, true);
    }

    private void terminateAfterInlineOutputCloseFailure(Throwable failure) {
        publicExitBarrier.inlineOutputFailed(failure);
        terminateAfterResourceCloseFailure(failure, false);
    }

    private void terminateAfterResourceCloseFailure(Throwable failure, boolean reportUnownedLateFailure) {
        boolean restoreInterrupt = Thread.interrupted() || SuppressionSupport.containsInterruption(failure);
        TerminalPublication publication = terminalArbiter.claimFailure(failure);
        try {
            if (publication != null) {
                stopProcessTreePreserving(failure);
            } else {
                forceCleanupPreserving(failure);
            }
            closeResourcesPreserving(failure);
            if (publication != null) {
                publication.publishFailure(failure);
            }
            if (publication == null) {
                Throwable primary = terminalFailure.get();
                if (primary != null) {
                    SuppressionSupport.attachDirect(primary, failure);
                } else if (reportUnownedLateFailure
                        && !publicExitBarrier.ownsInlineOutputFailure(failure)
                        && claimLateResourceCloseFailure(failure)) {
                    BoundedFailureReporter.shared().report(BoundedFailureReporter.captureFailureTarget(), failure);
                }
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeResourcesPreserving(Throwable primaryFailure) {
        try {
            closeResources();
        } catch (RuntimeException | Error cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
    }

    private boolean claimLateResourceCloseFailure(Throwable failure) {
        synchronized (reportedLateResourceCloseFailures) {
            return reportedLateResourceCloseFailures.add(failure);
        }
    }

    private static Throwable captureCleanupFailure(Throwable primaryFailure, Runnable cleanup) {
        try {
            cleanup.run();
            return primaryFailure;
        } catch (RuntimeException | Error cleanupFailure) {
            return SuppressionSupport.combine(primaryFailure, cleanupFailure);
        }
    }

    private static CompletableFuture<Void> physicalOutputCleanup(
            ProcessIoResources.Resource<InputStream> stdout, ProcessIoResources.Resource<InputStream> stderr) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.allOf(stdout.closeCompletion(), stderr.closeCompletion())
                .whenComplete((ignored, impossible) -> {
                    Throwable failure = prioritizeOutputCloseFailures(stdout.closeResult(), stderr.closeResult());
                    if (failure == null) {
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(failure);
                    }
                });
        return completion;
    }

    private static Throwable prioritizeOutputCloseFailures(Throwable stdoutFailure, Throwable stderrFailure) {
        if (stdoutFailure == null) {
            return stderrFailure;
        }
        if (stderrFailure == null) {
            return stdoutFailure;
        }
        Throwable primary =
                failurePriority(stderrFailure) > failurePriority(stdoutFailure) ? stderrFailure : stdoutFailure;
        Throwable secondary = primary == stdoutFailure ? stderrFailure : stdoutFailure;
        SuppressionSupport.attach(primary, secondary);
        return primary;
    }

    private static int failurePriority(Throwable failure) {
        if (failure instanceof Error) {
            return 2;
        }
        return failure instanceof RuntimeException ? 1 : 0;
    }

    private static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
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

    private static void cleanupProcessPreserving(
            Process process, Throwable primaryFailure, ConstructionRollback constructionRollback) {
        try {
            constructionRollback.cleanupProcess(process);
        } catch (Throwable cleanupFailure) {
            attachPreserving(primaryFailure, cleanupFailure);
        }
    }

    private static void attachPreserving(Throwable primaryFailure, Throwable secondaryFailure) {
        try {
            SuppressionSupport.attach(primaryFailure, secondaryFailure);
        } catch (Throwable ignored) {
            // Optional failure bookkeeping must not stop construction rollback.
        }
    }

    @FunctionalInterface
    interface WatcherStarter {

        Thread start(String threadPrefix, Runnable task);

        static WatcherStarter threading() {
            return Threading::start;
        }
    }

    enum ConstructionPoint {
        BEFORE_PROCESS_EXIT_OBSERVATION_CONSTRUCTION,
        BEFORE_PROCESS_TERMINAL_CONSTRUCTION,
        BEFORE_PUBLIC_EXIT_CONSTRUCTION,
        BEFORE_RESOURCES_CLOSED_CONSTRUCTION,
        BEFORE_LATE_FAILURE_SET_CONSTRUCTION,
        BEFORE_STATE_CONSTRUCTION,
        BEFORE_TERMINAL_FAILURE_CONSTRUCTION,
        BEFORE_DESCENDANTS_CONSTRUCTION,
        BEFORE_ACTIVITY_COUNTER_CONSTRUCTION,
        BEFORE_OUTPUT_OWNERSHIP_CONSTRUCTION,
        BEFORE_STDIN_LOCK_CONSTRUCTION,
        BEFORE_PROCESS_TREE_LOCK_CONSTRUCTION,
        BEFORE_TERMINAL_ARBITER_CONSTRUCTION,
        BEFORE_PROCESS_IO_ACQUISITION,
        AFTER_PROCESS_IO_ACQUISITION,
        BEFORE_STDIN_WRAPPER_CONSTRUCTION,
        BEFORE_OUTPUT_CLOSE_RESERVATION_CONSTRUCTION,
        BEFORE_STDOUT_CLOSE_WRAPPER_CONSTRUCTION,
        BEFORE_STDERR_CLOSE_WRAPPER_CONSTRUCTION,
        BEFORE_STDOUT_ACTIVITY_WRAPPER_CONSTRUCTION,
        BEFORE_STDERR_ACTIVITY_WRAPPER_CONSTRUCTION,
        BEFORE_PHYSICAL_OUTPUT_CLEANUP_CONSTRUCTION,
        BEFORE_PUBLIC_EXIT_BARRIER_CONSTRUCTION,
        BEFORE_EXIT_PUBLICATION_RESERVATION,
        AFTER_EXIT_PUBLICATION_TRANSFER,
        BEFORE_CLEANUP_OBSERVER_REGISTRATION,
        BEFORE_CONSTRUCTION_GATE_CONSTRUCTION,
        BEFORE_EXIT_WATCHER_START,
        AFTER_EXIT_WATCHER_START,
        BEFORE_IDLE_WATCHER_START,
        AFTER_IDLE_WATCHER_START
    }

    @FunctionalInterface
    interface ConstructionProbe {

        void at(ConstructionPoint point);
    }

    interface ConstructionRollback {

        default void release(BoundedLifecyclePublisher.Permit publication) {
            publication.release();
        }

        default void release(BoundedLifecyclePublisher.Reservation reservation) {
            reservation.release();
        }

        default void cleanupProcess(Process process) {
            ProcessLifecycle.forceStopWithoutStdinClose(process, Set.of(), CONSTRUCTION_FAILURE_CLEANUP_TIMEOUT);
        }

        default void rollback(ProcessIoResources resources, Throwable primaryFailure) {
            resources.rollbackConstruction(primaryFailure);
        }
    }

    private record ConstructionInputs(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            BoundedCloseDispatcher closeDispatcher,
            BoundedLifecyclePublisher resourcePublisher,
            BoundedLifecyclePublisher exitPublisher,
            ConstructionProbe constructionProbe,
            ConstructionRollback constructionRollback) {

        private static ConstructionInputs prepare(
                Process process,
                Duration idleTimeout,
                ShutdownPolicy shutdownPolicy,
                Charset charset,
                DiagnosticEmitter diagnostics,
                BoundedCloseDispatcher closeDispatcher,
                BoundedLifecyclePublisher resourcePublisher,
                BoundedLifecyclePublisher exitPublisher,
                ConstructionProbe constructionProbe,
                ConstructionRollback constructionRollback) {
            Objects.requireNonNull(process, "process");
            Duration validatedIdleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
            Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(closeDispatcher, "closeDispatcher");
            Objects.requireNonNull(resourcePublisher, "resourcePublisher");
            Objects.requireNonNull(exitPublisher, "exitPublisher");
            Objects.requireNonNull(constructionProbe, "constructionProbe");
            Objects.requireNonNull(constructionRollback, "constructionRollback");
            try {
                return new ConstructionInputs(
                        process,
                        validatedIdleTimeout,
                        shutdownPolicy,
                        charset,
                        diagnostics,
                        closeDispatcher,
                        resourcePublisher,
                        exitPublisher,
                        constructionProbe,
                        constructionRollback);
            } catch (RuntimeException | Error failure) {
                cleanupProcessPreserving(process, failure, constructionRollback);
                throw failure;
            }
        }
    }

    private static final class ConstructionLedger {

        private final Process process;
        private final ConstructionRollback constructionRollback;
        private ProcessIoResources ioResources;
        private BoundedLifecyclePublisher.Reservation exitReservation;
        private BoundedLifecyclePublisher.Permit exitPublication;
        private ConstructionGate gate;
        private boolean committed;

        private ConstructionLedger(Process process, ConstructionRollback constructionRollback) {
            this.process = process;
            this.constructionRollback = constructionRollback;
        }

        private void own(ProcessIoResources resources) {
            ioResources = resources;
        }

        private void own(BoundedLifecyclePublisher.Reservation reservation) {
            exitReservation = reservation;
        }

        private void own(BoundedLifecyclePublisher.Permit publication) {
            exitPublication = publication;
        }

        private void own(ConstructionGate constructionGate) {
            gate = constructionGate;
        }

        private void commit() {
            committed = true;
        }

        private void rollback(Throwable primaryFailure) {
            if (committed) {
                return;
            }
            if (gate != null) {
                try {
                    gate.abort();
                } catch (Throwable abortFailure) {
                    attachPreserving(primaryFailure, abortFailure);
                }
            }
            if (exitPublication != null) {
                try {
                    constructionRollback.release(exitPublication);
                } catch (Throwable releaseFailure) {
                    attachPreserving(primaryFailure, releaseFailure);
                }
            }
            if (exitReservation != null) {
                try {
                    constructionRollback.release(exitReservation);
                } catch (Throwable releaseFailure) {
                    attachPreserving(primaryFailure, releaseFailure);
                }
            }
            cleanupProcessPreserving(process, primaryFailure, constructionRollback);
            if (ioResources != null) {
                try {
                    constructionRollback.rollback(ioResources, primaryFailure);
                } catch (Throwable rollbackFailure) {
                    attachPreserving(primaryFailure, rollbackFailure);
                }
            }
        }
    }

    private static final class ConstructionGate {

        private final CountDownLatch decision = new CountDownLatch(1);
        private volatile boolean committed;

        private Runnable guard(Runnable task) {
            return () -> {
                boolean restoreInterrupt = false;
                while (true) {
                    try {
                        decision.await();
                        break;
                    } catch (InterruptedException interruption) {
                        restoreInterrupt = true;
                    }
                }
                try {
                    if (committed) {
                        task.run();
                    }
                } finally {
                    if (restoreInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }

        private void commit() {
            committed = true;
            decision.countDown();
        }

        private void abort() {
            decision.countDown();
        }
    }

    final class HelperCleanupRegistration {

        private boolean settled;

        void complete() {
            synchronized (this) {
                if (settled) {
                    return;
                }
                settled = true;
            }
            publicExitBarrier.helperCompleted();
        }

        void rollback() {
            complete();
        }
    }

    private final class PublicExitBarrier implements Runnable {

        private final Object lock = new Object();
        private int helpers;
        private boolean processCompleted;
        private boolean physicalOutputCompleted;
        private boolean publicationClaimed;
        private SessionExit processResult;
        private Throwable processFailure;
        private Throwable inlineOutputFailure;
        private Throwable firstInlineOutputFailure;
        private Throwable secondInlineOutputFailure;
        private SessionExit publicationResult;
        private Throwable publicationFailure;

        private HelperCleanupRegistration registerHelper() {
            HelperCleanupRegistration registration = new HelperCleanupRegistration();
            synchronized (lock) {
                if (publicationClaimed) {
                    throw new IllegalStateException("Session cleanup publication has already been claimed");
                }
                helpers++;
            }
            return registration;
        }

        private void processCompleted(SessionExit result, Throwable failure) {
            boolean publish;
            synchronized (lock) {
                if (processCompleted) {
                    throw new IllegalStateException("Process terminal cleanup was already recorded");
                }
                processCompleted = true;
                processResult = result;
                processFailure = failure;
                publish = claimPublicationLocked();
            }
            publishIfReady(publish);
        }

        private void physicalOutputCompleted() {
            boolean publish;
            synchronized (lock) {
                if (physicalOutputCompleted) {
                    throw new IllegalStateException("Physical output cleanup was already recorded");
                }
                physicalOutputCompleted = true;
                publish = claimPublicationLocked();
            }
            publishIfReady(publish);
        }

        private void inlineOutputFailed(Throwable failure) {
            synchronized (lock) {
                if (firstInlineOutputFailure == null) {
                    firstInlineOutputFailure = failure;
                } else if (firstInlineOutputFailure != failure && secondInlineOutputFailure == null) {
                    secondInlineOutputFailure = failure;
                }
                inlineOutputFailure = SuppressionSupport.combine(inlineOutputFailure, failure);
            }
        }

        private boolean ownsInlineOutputFailure(Throwable failure) {
            synchronized (lock) {
                return firstInlineOutputFailure == failure || secondInlineOutputFailure == failure;
            }
        }

        private void helperCompleted() {
            boolean publish;
            synchronized (lock) {
                if (helpers <= 0) {
                    throw new IllegalStateException("Helper cleanup barrier accounting underflow");
                }
                helpers--;
                publish = claimPublicationLocked();
            }
            publishIfReady(publish);
        }

        private boolean claimPublicationLocked() {
            if (publicationClaimed || !processCompleted || !physicalOutputCompleted || helpers != 0) {
                return false;
            }
            publicationClaimed = true;
            publicationResult = processResult;
            publicationFailure = SuppressionSupport.combine(processFailure, inlineOutputFailure);
            return true;
        }

        private void publishIfReady(boolean publish) {
            if (publish) {
                exitPublication.publish(this);
            }
        }

        @Override
        public void run() {
            if (publicationFailure == null) {
                exit.complete(publicationResult);
            } else {
                exit.completeExceptionally(publicationFailure);
            }
        }
    }

    private final class TerminalArbiter {

        private TerminalPublication claimNaturalSuccess() {
            return claim(this::claimNaturalExit);
        }

        private TerminalPublication claimCloseSuccess() {
            return claim(() -> state.compareAndSet(State.CLOSING, State.CLOSED));
        }

        private TerminalPublication claimFailure(Throwable failure) {
            TerminalPublication publication = claim(this::claimFailureState);
            if (publication != null) {
                terminalFailure.compareAndSet(null, Objects.requireNonNull(failure, "failure"));
            }
            return publication;
        }

        private TerminalPublication claim(TerminalClaim claim) {
            if (!claim.tryClaim()) {
                return null;
            }
            return new TerminalPublication();
        }

        private boolean claimNaturalExit() {
            while (true) {
                State current = state.get();
                if (current == State.CLOSING || current == State.CLOSED) {
                    return false;
                }
                if (state.compareAndSet(current, State.CLOSED)) {
                    return true;
                }
            }
        }

        private boolean claimFailureState() {
            while (true) {
                State current = state.get();
                if (current == State.CLOSED) {
                    return false;
                }
                if (state.compareAndSet(current, State.CLOSED)) {
                    return true;
                }
            }
        }
    }

    private final class TerminalPublication {

        private final AtomicBoolean published = new AtomicBoolean();

        private void publishSuccess(SessionExit sessionExit) {
            claimPublication();
            try {
                diagnostics.emit(
                        DiagnosticEventType.PROCESS_EXITED,
                        exitAttributes(sessionExit.exitCode(), sessionExit.timedOut()));
                processTerminal.complete(sessionExit);
            } catch (RuntimeException | Error publicationFailure) {
                processTerminal.completeExceptionally(publicationFailure);
                throw publicationFailure;
            }
        }

        private void publishFailure(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            claimPublication();
            terminalFailure.compareAndSet(null, failure);
            emitPreserving(
                    DiagnosticEventType.SHUTDOWN_REQUESTED, DiagnosticEmitter.attributes("reason", "failure"), failure);
            emitPreserving(DiagnosticEventType.PROCESS_FAILED, DiagnosticEmitter.failureAttributes(failure), failure);
            processTerminal.completeExceptionally(failure);
        }

        private void claimPublication() {
            if (!published.compareAndSet(false, true)) {
                throw new IllegalStateException("Terminal outcome has already been published");
            }
        }
    }

    @FunctionalInterface
    private interface TerminalClaim {

        boolean tryClaim();
    }

    private enum State {
        RUNNING,
        STDIN_CLOSED,
        CLOSING,
        CLOSED
    }

    static final class ProcessExitObservation {

        private final Object lock = new Object();
        private final AtomicReference<OptionalInt> snapshot = new AtomicReference<>(OptionalInt.empty());
        private Registration first;
        private int subscriberCount;
        private long deliveryCount;

        Registration subscribe(AtomicReference<OptionalInt> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            Registration registration = new Registration(this, subscriber);
            synchronized (lock) {
                OptionalInt observed = snapshot.get();
                if (observed.isPresent()) {
                    registration.deliverLocked(observed);
                } else {
                    linkLocked(registration);
                }
            }
            return registration;
        }

        void publish(int exitCode) {
            OptionalInt observed = OptionalInt.of(exitCode);
            synchronized (lock) {
                if (snapshot.get().isPresent()) {
                    return;
                }
                snapshot.set(observed);
                Registration current = first;
                while (current != null) {
                    Registration next = current.next;
                    unlinkLocked(current);
                    current.deliverLocked(observed);
                    current = next;
                }
            }
        }

        int subscriberCount() {
            synchronized (lock) {
                return subscriberCount;
            }
        }

        long deliveryCount() {
            synchronized (lock) {
                return deliveryCount;
            }
        }

        private void linkLocked(Registration registration) {
            registration.next = first;
            if (first != null) {
                first.previous = registration;
            }
            first = registration;
            registration.linked = true;
            subscriberCount++;
        }

        private void unlinkLocked(Registration registration) {
            if (!registration.linked) {
                return;
            }
            if (registration.previous == null) {
                first = registration.next;
            } else {
                registration.previous.next = registration.next;
            }
            if (registration.next != null) {
                registration.next.previous = registration.previous;
            }
            registration.previous = null;
            registration.next = null;
            registration.linked = false;
            subscriberCount--;
        }

        static final class Registration implements AutoCloseable {

            private final ProcessExitObservation owner;
            private final AtomicReference<OptionalInt> subscriber;
            private Registration previous;
            private Registration next;
            private boolean linked;
            private boolean settled;

            private Registration(ProcessExitObservation owner, AtomicReference<OptionalInt> subscriber) {
                this.owner = owner;
                this.subscriber = subscriber;
            }

            @Override
            public void close() {
                synchronized (owner.lock) {
                    if (settled) {
                        return;
                    }
                    owner.unlinkLocked(this);
                    settled = true;
                }
            }

            private void deliverLocked(OptionalInt observed) {
                if (settled) {
                    return;
                }
                subscriber.set(observed);
                settled = true;
                owner.deliveryCount++;
            }
        }
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
                ensureCanWrite();
                delegate.flush();
            }
        }

        @Override
        public void close() {
            closeStdin();
        }
    }
}
