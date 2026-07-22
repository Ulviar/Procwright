/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class WorkerCloseSupport {

    private WorkerCloseSupport() {}

    static WorkerPoolController.CloseObservation initiateCloseAndObserve(
            AutoCloseable session,
            CompletableFuture<?> terminalOutcome,
            CompletableFuture<?> physicalOutputCleanup,
            PoolRetirementDispatcher.Admission admission) {
        return initiateCloseAndObserve(
                session,
                terminalOutcome,
                physicalOutputCleanup,
                admission,
                PoolRetirementDispatcher::executeWorkerClose);
    }

    static WorkerPoolController.CloseObservation initiateCloseAndObserve(
            AutoCloseable session,
            CompletableFuture<?> terminalOutcome,
            CompletableFuture<?> physicalOutputCleanup,
            PoolRetirementDispatcher.Admission admission,
            WorkerPoolController.TerminalRetirementDispatcher dispatcher) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(terminalOutcome, "terminalOutcome");
        Objects.requireNonNull(physicalOutputCleanup, "physicalOutputCleanup");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(dispatcher, "dispatcher");

        PoolRetirementDispatcher.Ownership closeOwnership = initiateClose(session, admission, dispatcher);
        CompletableFuture<Throwable> closeFailure = observe(closeOwnership.cleanupCompletion());
        CompletableFuture<Throwable> terminalFailure = observe(terminalOutcome);
        CompletableFuture<Throwable> physicalCleanupFailure = observe(physicalOutputCleanup);
        CompletableFuture<WorkerPoolController.CloseOutcome> cleanupOutcome = CompletableFuture.allOf(
                        closeFailure, terminalFailure, physicalCleanupFailure)
                .thenApply(ignored ->
                        aggregate(closeFailure.join(), terminalFailure.join(), physicalCleanupFailure.join()));
        return () -> cleanupOutcome;
    }

    private static PoolRetirementDispatcher.Ownership initiateClose(
            AutoCloseable session,
            PoolRetirementDispatcher.Admission admission,
            WorkerPoolController.TerminalRetirementDispatcher dispatcher) {
        try {
            return dispatcher.dispatch(admission, () -> {
                try {
                    session.close();
                } catch (Throwable failure) {
                    throw new CloseTaskFailure(failure);
                }
            });
        } catch (Throwable failure) {
            CompletableFuture<Thread> started = CompletableFuture.failedFuture(failure);
            CompletableFuture<Void> completion = CompletableFuture.failedFuture(failure);
            return new PoolRetirementDispatcher.Ownership(started, completion, completion);
        }
    }

    private static CompletableFuture<Throwable> observe(CompletableFuture<?> future) {
        return future.handle((ignored, failure) -> unwrap(failure));
    }

    private static WorkerPoolController.CloseOutcome aggregate(Throwable... observedFailures) {
        WorkerCloseFailureAccumulator failures = new WorkerCloseFailureAccumulator();
        for (Throwable failure : observedFailures) {
            failures.add(unwrapCloseTaskFailure(failure));
        }
        Throwable failure = failures.failure();
        return failure == null
                ? WorkerPoolController.CloseOutcome.success()
                : WorkerPoolController.CloseOutcome.failure(failure);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException completion && completion.getCause() != null
                ? completion.getCause()
                : failure;
    }

    private static Throwable unwrapCloseTaskFailure(Throwable failure) {
        return failure instanceof CloseTaskFailure closeTaskFailure && closeTaskFailure.getCause() != null
                ? closeTaskFailure.getCause()
                : failure;
    }

    @SuppressWarnings("serial")
    private static final class CloseTaskFailure extends RuntimeException {

        private CloseTaskFailure(Throwable cause) {
            super(cause);
        }
    }
}
