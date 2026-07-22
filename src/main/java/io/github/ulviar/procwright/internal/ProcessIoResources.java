/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Transactionally acquires one stable reference to every process stream and owns their exact-once physical close.
 *
 * @hidden
 */
public final class ProcessIoResources {

    private static final Duration ACQUISITION_FAILURE_CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private static final Consumer<Throwable> IGNORE_INLINE_CLOSE_FAILURE = ignored -> {};
    private static final ConstructionProbe NO_CONSTRUCTION_FAILURES = point -> {};
    private static final ConstructionRollback DEFAULT_CONSTRUCTION_ROLLBACK = new ConstructionRollback() {};

    private final Resource<OutputStream> stdin;
    private final Resource<InputStream> stdout;
    private final Resource<InputStream> stderr;

    private ProcessIoResources(
            Resource<OutputStream> stdin, Resource<InputStream> stdout, Resource<InputStream> stderr) {
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public static ProcessIoResources acquire(Process process) {
        return acquire(
                process,
                BoundedCloseDispatcher.shared(),
                BoundedLifecyclePublisher.shared(),
                IGNORE_INLINE_CLOSE_FAILURE,
                NO_CONSTRUCTION_FAILURES,
                DEFAULT_CONSTRUCTION_ROLLBACK);
    }

    public static ProcessIoResources acquire(Process process, BoundedCloseDispatcher dispatcher) {
        return acquire(
                process,
                dispatcher,
                BoundedLifecyclePublisher.shared(),
                IGNORE_INLINE_CLOSE_FAILURE,
                NO_CONSTRUCTION_FAILURES,
                DEFAULT_CONSTRUCTION_ROLLBACK);
    }

    static ProcessIoResources acquire(
            Process process, BoundedCloseDispatcher dispatcher, BoundedLifecyclePublisher lifecyclePublisher) {
        return acquire(
                process,
                dispatcher,
                lifecyclePublisher,
                IGNORE_INLINE_CLOSE_FAILURE,
                NO_CONSTRUCTION_FAILURES,
                DEFAULT_CONSTRUCTION_ROLLBACK);
    }

    public static ProcessIoResources acquire(
            Process process,
            BoundedCloseDispatcher dispatcher,
            BoundedLifecyclePublisher lifecyclePublisher,
            Consumer<? super Throwable> inlineOutputCloseFailureHandler) {
        return acquire(
                process,
                dispatcher,
                lifecyclePublisher,
                inlineOutputCloseFailureHandler,
                NO_CONSTRUCTION_FAILURES,
                DEFAULT_CONSTRUCTION_ROLLBACK);
    }

    static ProcessIoResources acquire(
            Process process,
            BoundedCloseDispatcher dispatcher,
            BoundedLifecyclePublisher lifecyclePublisher,
            Consumer<? super Throwable> inlineOutputCloseFailureHandler,
            ConstructionProbe constructionProbe) {
        return acquire(
                process,
                dispatcher,
                lifecyclePublisher,
                inlineOutputCloseFailureHandler,
                constructionProbe,
                DEFAULT_CONSTRUCTION_ROLLBACK);
    }

    static ProcessIoResources acquire(
            Process process,
            BoundedCloseDispatcher dispatcher,
            BoundedLifecyclePublisher lifecyclePublisher,
            Consumer<? super Throwable> inlineOutputCloseFailureHandler,
            ConstructionProbe constructionProbe,
            ConstructionRollback constructionRollback) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(lifecyclePublisher, "lifecyclePublisher");
        Objects.requireNonNull(inlineOutputCloseFailureHandler, "inlineOutputCloseFailureHandler");
        Objects.requireNonNull(constructionProbe, "constructionProbe");
        Objects.requireNonNull(constructionRollback, "constructionRollback");
        BoundedCloseDispatcher.Reservation closeReservation;
        try {
            closeReservation = dispatcher.reserve(3);
        } catch (RuntimeException | Error failure) {
            cleanupProcessPreserving(process, failure, constructionRollback);
            throw failure;
        }
        BoundedLifecyclePublisher.Reservation publicationReservation;
        try {
            publicationReservation = lifecyclePublisher.reserve(3);
        } catch (RuntimeException | Error failure) {
            releasePreserving(closeReservation, failure, constructionRollback);
            cleanupProcessPreserving(process, failure, constructionRollback);
            throw failure;
        }

        ConstructionLedger ledger;
        try {
            ledger = new ConstructionLedger(closeReservation, publicationReservation, constructionRollback);
        } catch (RuntimeException | Error failure) {
            releasePreserving(closeReservation, failure, constructionRollback);
            releasePreserving(publicationReservation, failure, constructionRollback);
            cleanupProcessPreserving(process, failure, constructionRollback);
            throw failure;
        }
        try {
            ledger.transferPermits();
            constructionProbe.at(ConstructionPoint.BEFORE_CLOSE_CLAIM_LOCK);
            Object closeClaimLock = new Object();
            constructionProbe.at(ConstructionPoint.AFTER_CLOSE_CLAIM_LOCK);

            constructionProbe.at(ConstructionPoint.BEFORE_STDIN_STREAM_ACQUISITION);
            OutputStream stdinStream = process.getOutputStream();
            ledger.stdin.stream = stdinStream;
            constructionProbe.at(ConstructionPoint.AFTER_STDIN_STREAM_ACQUISITION);
            constructionProbe.at(ConstructionPoint.BEFORE_STDIN_RESOURCE_CONSTRUCTION);
            Resource<OutputStream> stdin = new Resource<>(
                    stdinStream,
                    ledger.stdin.closePermit,
                    ledger.stdin.publicationPermit,
                    closeClaimLock,
                    IGNORE_INLINE_CLOSE_FAILURE);
            ledger.stdin.resource = stdin;
            constructionProbe.at(ConstructionPoint.AFTER_STDIN_RESOURCE_CONSTRUCTION);

            constructionProbe.at(ConstructionPoint.BEFORE_STDOUT_STREAM_ACQUISITION);
            InputStream stdoutStream = process.getInputStream();
            ledger.stdout.stream = stdoutStream;
            constructionProbe.at(ConstructionPoint.AFTER_STDOUT_STREAM_ACQUISITION);
            constructionProbe.at(ConstructionPoint.BEFORE_STDOUT_RESOURCE_CONSTRUCTION);
            Resource<InputStream> stdout = new Resource<>(
                    stdoutStream,
                    ledger.stdout.closePermit,
                    ledger.stdout.publicationPermit,
                    closeClaimLock,
                    inlineOutputCloseFailureHandler);
            ledger.stdout.resource = stdout;
            constructionProbe.at(ConstructionPoint.AFTER_STDOUT_RESOURCE_CONSTRUCTION);

            constructionProbe.at(ConstructionPoint.BEFORE_STDERR_STREAM_ACQUISITION);
            InputStream stderrStream = process.getErrorStream();
            ledger.stderr.stream = stderrStream;
            constructionProbe.at(ConstructionPoint.AFTER_STDERR_STREAM_ACQUISITION);
            constructionProbe.at(ConstructionPoint.BEFORE_STDERR_RESOURCE_CONSTRUCTION);
            Resource<InputStream> stderr = new Resource<>(
                    stderrStream,
                    ledger.stderr.closePermit,
                    ledger.stderr.publicationPermit,
                    closeClaimLock,
                    inlineOutputCloseFailureHandler);
            ledger.stderr.resource = stderr;
            constructionProbe.at(ConstructionPoint.AFTER_STDERR_RESOURCE_CONSTRUCTION);

            constructionProbe.at(ConstructionPoint.BEFORE_CONTAINER_CONSTRUCTION);
            ProcessIoResources resources = new ProcessIoResources(stdin, stdout, stderr);
            constructionProbe.at(ConstructionPoint.AFTER_CONTAINER_CONSTRUCTION);
            ledger.commit();
            return resources;
        } catch (RuntimeException | Error failure) {
            cleanupProcessPreserving(process, failure, constructionRollback);
            ledger.rollback(failure);
            throw failure;
        }
    }

    public Resource<OutputStream> stdin() {
        return stdin;
    }

    public Resource<InputStream> stdout() {
        return stdout;
    }

    public Resource<InputStream> stderr() {
        return stderr;
    }

    public void closeAllAsync(Consumer<? super Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
        dispatchAll(
                () -> stdin.closeAsync("procwright-process-stdin-close-", failureHandler),
                () -> stdout.closeAsync("procwright-process-stdout-close-", failureHandler),
                () -> stderr.closeAsync("procwright-process-stderr-close-", failureHandler));
    }

    public void closeOutputsAsync(Consumer<? super Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
        dispatchAll(
                () -> stdout.closeAsync("procwright-process-stdout-close-", failureHandler),
                () -> stderr.closeAsync("procwright-process-stderr-close-", failureHandler));
    }

    public void rollbackConstruction(Throwable primaryFailure) {
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        rollbackPreserving(stdin, primaryFailure);
        rollbackPreserving(stdout, primaryFailure);
        rollbackPreserving(stderr, primaryFailure);
    }

    private static void dispatchAll(Runnable... dispatches) {
        Throwable failure = null;
        for (Runnable dispatch : dispatches) {
            try {
                dispatch.run();
            } catch (RuntimeException | Error dispatchFailure) {
                failure = SuppressionSupport.combine(failure, dispatchFailure);
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    public Throwable awaitClose(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        CompletableFuture<Void> all =
                CompletableFuture.allOf(stdin.closeCompletion(), stdout.closeCompletion(), stderr.closeCompletion());
        try {
            if (timeout.isZero()) {
                all.get();
            } else {
                all.get(DurationSupport.saturatedMillis(timeout), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return new CommandExecutionException("Interrupted while closing process streams", interruption);
        } catch (TimeoutException timeoutFailure) {
            return new CommandExecutionException("Timed out while closing process streams", timeoutFailure);
        } catch (ExecutionException impossible) {
            return new AssertionError("process close completion stores failures as values", impossible);
        }
        Throwable failure = null;
        failure = SuppressionSupport.combine(failure, stdin.closeResult());
        failure = SuppressionSupport.combine(failure, stdout.closeResult());
        return SuppressionSupport.combine(failure, stderr.closeResult());
    }

    public static void closePairAsync(
            Resource<? extends Closeable> first,
            String firstThreadPrefix,
            Consumer<? super Throwable> firstFailureHandler,
            Runnable firstCompletionHandler,
            Resource<? extends Closeable> second,
            String secondThreadPrefix,
            Consumer<? super Throwable> secondFailureHandler,
            Runnable secondCompletionHandler) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first == second || first.closeClaimLock != second.closeClaimLock) {
            throw new IllegalArgumentException("Paired close resources must be distinct owners from one process");
        }
        synchronized (first.closeClaimLock) {
            if (first.closeClaimed.get() || second.closeClaimed.get()) {
                throw new IllegalStateException("Paired process output close has already started");
            }
            first.closeClaimed.set(true);
            second.closeClaimed.set(true);
        }
        BoundedCloseDispatcher.CloseRequest firstRequest =
                first.ownedCloseRequest(firstThreadPrefix, firstFailureHandler, firstCompletionHandler);
        BoundedCloseDispatcher.CloseRequest secondRequest =
                second.ownedCloseRequest(secondThreadPrefix, secondFailureHandler, secondCompletionHandler);
        first.closePermit.dispatchPair(firstRequest, second.closePermit, secondRequest);
    }

    private static void cleanupProcessPreserving(
            Process process, Throwable primaryFailure, ConstructionRollback constructionRollback) {
        try {
            constructionRollback.cleanupProcess(process);
        } catch (Throwable cleanupFailure) {
            attachPreserving(primaryFailure, cleanupFailure);
        }
    }

    private static void releasePreserving(
            BoundedCloseDispatcher.Reservation reservation,
            Throwable primaryFailure,
            ConstructionRollback constructionRollback) {
        try {
            constructionRollback.release(reservation);
        } catch (Throwable releaseFailure) {
            attachPreserving(primaryFailure, releaseFailure);
        }
    }

    private static void releasePreserving(
            BoundedLifecyclePublisher.Reservation reservation,
            Throwable primaryFailure,
            ConstructionRollback constructionRollback) {
        try {
            constructionRollback.release(reservation);
        } catch (Throwable releaseFailure) {
            attachPreserving(primaryFailure, releaseFailure);
        }
    }

    private static void rollbackPreserving(Resource<?> resource, Throwable primaryFailure) {
        try {
            resource.rollbackConstruction(primaryFailure);
        } catch (Throwable rollbackFailure) {
            attachPreserving(primaryFailure, rollbackFailure);
        }
    }

    private static void attachPreserving(Throwable primaryFailure, Throwable secondaryFailure) {
        try {
            SuppressionSupport.attach(primaryFailure, secondaryFailure);
        } catch (Throwable ignored) {
            // Optional failure bookkeeping must not stop construction rollback.
        }
    }

    enum ConstructionPoint {
        BEFORE_CLOSE_CLAIM_LOCK,
        AFTER_CLOSE_CLAIM_LOCK,
        BEFORE_STDIN_STREAM_ACQUISITION,
        AFTER_STDIN_STREAM_ACQUISITION,
        BEFORE_STDIN_RESOURCE_CONSTRUCTION,
        AFTER_STDIN_RESOURCE_CONSTRUCTION,
        BEFORE_STDOUT_STREAM_ACQUISITION,
        AFTER_STDOUT_STREAM_ACQUISITION,
        BEFORE_STDOUT_RESOURCE_CONSTRUCTION,
        AFTER_STDOUT_RESOURCE_CONSTRUCTION,
        BEFORE_STDERR_STREAM_ACQUISITION,
        AFTER_STDERR_STREAM_ACQUISITION,
        BEFORE_STDERR_RESOURCE_CONSTRUCTION,
        AFTER_STDERR_RESOURCE_CONSTRUCTION,
        BEFORE_CONTAINER_CONSTRUCTION,
        AFTER_CONTAINER_CONSTRUCTION
    }

    @FunctionalInterface
    interface ConstructionProbe {

        void at(ConstructionPoint point);
    }

    interface ConstructionRollback {

        default void cleanupProcess(Process process) {
            ProcessLifecycle.forceStopWithoutStdinClose(process, Set.of(), ACQUISITION_FAILURE_CLEANUP_TIMEOUT);
        }

        default void release(BoundedCloseDispatcher.Reservation reservation) {
            reservation.release();
        }

        default void release(BoundedLifecyclePublisher.Reservation reservation) {
            reservation.release();
        }

        default void release(BoundedCloseDispatcher.Permit permit) {
            permit.release();
        }

        default void release(BoundedLifecyclePublisher.Permit permit) {
            permit.release();
        }

        default void closeInline(BoundedCloseDispatcher.Permit permit, Closeable stream) throws IOException {
            permit.closeInline(stream);
        }

        default void rollback(Resource<?> resource, Throwable primaryFailure) {
            resource.rollbackConstruction(primaryFailure);
        }
    }

    private static final class ConstructionLedger {

        private final BoundedCloseDispatcher.Reservation closeReservation;
        private final BoundedLifecyclePublisher.Reservation publicationReservation;
        private final ConstructionRollback constructionRollback;
        private final ResourceSlot stdin = new ResourceSlot();
        private final ResourceSlot stdout = new ResourceSlot();
        private final ResourceSlot stderr = new ResourceSlot();
        private boolean committed;

        private ConstructionLedger(
                BoundedCloseDispatcher.Reservation closeReservation,
                BoundedLifecyclePublisher.Reservation publicationReservation,
                ConstructionRollback constructionRollback) {
            this.closeReservation = closeReservation;
            this.publicationReservation = publicationReservation;
            this.constructionRollback = constructionRollback;
        }

        private void transferPermits() {
            stdin.closePermit = closeReservation.takePermit();
            stdout.closePermit = closeReservation.takePermit();
            stderr.closePermit = closeReservation.takePermit();
            stdin.publicationPermit = publicationReservation.takePermit();
            stdout.publicationPermit = publicationReservation.takePermit();
            stderr.publicationPermit = publicationReservation.takePermit();
        }

        private void commit() {
            committed = true;
        }

        private void rollback(Throwable primaryFailure) {
            if (committed) {
                return;
            }
            releasePreserving(closeReservation, primaryFailure, constructionRollback);
            releasePreserving(publicationReservation, primaryFailure, constructionRollback);
            stdin.rollback(primaryFailure, constructionRollback);
            stdout.rollback(primaryFailure, constructionRollback);
            stderr.rollback(primaryFailure, constructionRollback);
        }
    }

    private static final class ResourceSlot {

        private BoundedCloseDispatcher.Permit closePermit;
        private BoundedLifecyclePublisher.Permit publicationPermit;
        private Closeable stream;
        private Resource<? extends Closeable> resource;

        private void rollback(Throwable primaryFailure, ConstructionRollback constructionRollback) {
            if (resource != null) {
                try {
                    constructionRollback.rollback(resource, primaryFailure);
                } catch (Throwable rollbackFailure) {
                    attachPreserving(primaryFailure, rollbackFailure);
                }
            } else {
                releaseUntransferredResource(primaryFailure, constructionRollback);
            }
        }

        private void releaseUntransferredResource(Throwable primaryFailure, ConstructionRollback constructionRollback) {
            if (publicationPermit != null) {
                try {
                    constructionRollback.release(publicationPermit);
                } catch (Throwable releaseFailure) {
                    attachPreserving(primaryFailure, releaseFailure);
                }
            }
            if (closePermit == null) {
                return;
            }
            if (stream == null) {
                try {
                    constructionRollback.release(closePermit);
                } catch (Throwable releaseFailure) {
                    attachPreserving(primaryFailure, releaseFailure);
                }
                return;
            }
            try {
                constructionRollback.closeInline(closePermit, stream);
            } catch (Throwable closeFailure) {
                attachPreserving(primaryFailure, closeFailure);
            }
        }
    }

    /** Owns one stable stream reference, one close admission, and one physical close attempt. */
    public static final class Resource<T extends Closeable> {

        private final T stream;
        private final BoundedCloseDispatcher.Permit closePermit;
        private final BoundedLifecyclePublisher.Permit publicationPermit;
        private final Object closeClaimLock;
        private final Consumer<? super Throwable> inlineCloseFailureHandler;
        private final AtomicBoolean closeClaimed = new AtomicBoolean();
        private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
        private Throwable closeFailure;

        private Resource(
                T stream,
                BoundedCloseDispatcher.Permit closePermit,
                BoundedLifecyclePublisher.Permit publicationPermit,
                Object closeClaimLock,
                Consumer<? super Throwable> inlineCloseFailureHandler) {
            this.stream = Objects.requireNonNull(stream, "process stream");
            this.closePermit = Objects.requireNonNull(closePermit, "closePermit");
            this.publicationPermit = Objects.requireNonNull(publicationPermit, "publicationPermit");
            this.closeClaimLock = Objects.requireNonNull(closeClaimLock, "closeClaimLock");
            this.inlineCloseFailureHandler =
                    Objects.requireNonNull(inlineCloseFailureHandler, "inlineCloseFailureHandler");
        }

        public T stream() {
            return stream;
        }

        public boolean closeStarted() {
            return closeClaimed.get();
        }

        public void closeInline() throws IOException {
            if (!claimClose()) {
                return;
            }
            Throwable failure = null;
            try {
                closePermit.closeInline(stream);
            } catch (IOException | RuntimeException | Error closeFailure) {
                failure = closeFailure;
                notifyInlineCloseFailure(closeFailure);
                throw closeFailure;
            } finally {
                settleClose(failure);
            }
        }

        private void notifyInlineCloseFailure(Throwable failure) {
            try {
                inlineCloseFailureHandler.accept(failure);
            } catch (Throwable callbackFailure) {
                SuppressionSupport.attach(failure, callbackFailure);
            }
        }

        private void rollbackConstruction(Throwable primaryFailure) {
            if (!claimClose()) {
                return;
            }
            try {
                closePermit.closeInline(stream);
            } catch (Throwable closeFailure) {
                attachPreserving(primaryFailure, closeFailure);
            }
            try {
                publicationPermit.release();
            } catch (Throwable releaseFailure) {
                attachPreserving(primaryFailure, releaseFailure);
            }
        }

        public void closeAsync(String threadPrefix, Consumer<? super Throwable> failureHandler) {
            closeAsync(threadPrefix, failureHandler, () -> {});
        }

        public void closeAsync(
                String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(failureHandler, "failureHandler");
            Objects.requireNonNull(completionHandler, "completionHandler");
            if (!claimClose()) {
                observeExistingClose(failureHandler, completionHandler);
                return;
            }
            BoundedCloseDispatcher.DispatchOutcome dispatchOutcome;
            try {
                dispatchOutcome =
                        closePermit.dispatchOutcome(closeRequest(threadPrefix, failureHandler, completionHandler));
            } catch (RuntimeException | Error dispatchFailure) {
                recordDispatchFailure(dispatchFailure);
                throw dispatchFailure;
            }
            dispatchOutcome.rethrowStartFailure();
        }

        public void closeOwnedAsync(
                String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(failureHandler, "failureHandler");
            Objects.requireNonNull(completionHandler, "completionHandler");
            if (!claimClose()) {
                observeExistingClose(failureHandler, completionHandler);
                return;
            }
            BoundedCloseDispatcher.DispatchOutcome dispatchOutcome;
            try {
                dispatchOutcome =
                        closePermit.dispatchOutcome(ownedCloseRequest(threadPrefix, failureHandler, completionHandler));
            } catch (RuntimeException | Error dispatchFailure) {
                recordDispatchFailure(dispatchFailure);
                throw dispatchFailure;
            }
            dispatchOutcome.rethrowStartFailure();
        }

        public CompletableFuture<Void> closeCompletion() {
            return closeCompletion.copy();
        }

        public Throwable closeResult() {
            synchronized (closeClaimLock) {
                return closeCompletion.isDone() ? closeFailure : null;
            }
        }

        private boolean claimClose() {
            synchronized (closeClaimLock) {
                return closeClaimed.compareAndSet(false, true);
            }
        }

        private BoundedCloseDispatcher.CloseRequest closeRequest(
                String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(failureHandler, "failureHandler");
            Objects.requireNonNull(completionHandler, "completionHandler");
            return BoundedCloseDispatcher.ownedCloseRequest(
                    stream, threadPrefix, this::settleClose, failureHandler, completionHandler);
        }

        private BoundedCloseDispatcher.CloseRequest ownedCloseRequest(
                String threadPrefix, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(failureHandler, "failureHandler");
            Objects.requireNonNull(completionHandler, "completionHandler");
            return BoundedCloseDispatcher.ownedCloseRequest(
                    stream,
                    threadPrefix,
                    failure -> settleOwnedClose(failure, failureHandler, completionHandler),
                    ignored -> {},
                    () -> {});
        }

        private void settleOwnedClose(
                Throwable physicalFailure, Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
            recordCloseFailure(physicalFailure);
            BoundedFailureReporter.FailureTarget failureTarget = BoundedFailureReporter.captureFailureTarget();
            publicationPermit.publish(() -> BoundedFailureReporter.withFailureTarget(failureTarget, () -> {
                Throwable callbackFailure = null;
                try {
                    if (physicalFailure != null) {
                        failureHandler.accept(physicalFailure);
                    }
                } catch (Throwable failure) {
                    callbackFailure = failure;
                }
                try {
                    completionHandler.run();
                } catch (Throwable failure) {
                    callbackFailure = SuppressionSupport.combine(callbackFailure, failure);
                }
                recordCloseFailure(callbackFailure);
                if (callbackFailure != null) {
                    BoundedFailureReporter.shared().report(failureTarget, callbackFailure);
                }
                closeCompletion.complete(null);
            }));
        }

        private void settleClose(Throwable physicalFailure) {
            recordCloseFailure(physicalFailure);
            publicationPermit.publish(() -> closeCompletion.complete(null));
        }

        private void recordDispatchFailure(Throwable dispatchFailure) {
            recordCloseFailure(dispatchFailure);
            publicationPermit.publish(() -> closeCompletion.complete(null));
        }

        private void recordCloseFailure(Throwable failure) {
            if (failure != null) {
                synchronized (closeClaimLock) {
                    closeFailure = SuppressionSupport.combine(closeFailure, failure);
                }
            }
        }

        private void observeExistingClose(Consumer<? super Throwable> failureHandler, Runnable completionHandler) {
            closeCompletion.whenComplete((ignored, impossible) -> {
                Thread sourceThread = Thread.currentThread();
                BoundedFailureReporter.shared().execute(sourceThread, () -> {
                    Throwable failure;
                    synchronized (closeClaimLock) {
                        failure = closeFailure;
                    }
                    if (failure != null) {
                        failureHandler.accept(failure);
                    }
                });
                BoundedFailureReporter.shared().execute(sourceThread, () -> {
                    completionHandler.run();
                });
            });
        }
    }
}
