/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.Threading;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkerCloseSupport {

    private static final String CLOSE_THREAD_PREFIX = "procwright-worker-close-";

    private WorkerCloseSupport() {}

    static CompletableFuture<WorkerRetirement.Outcome> closeOutcome(
            AutoCloseable session, CompletableFuture<?> terminalOutcome) {
        return closeOutcome(session, terminalOutcome, Threading::start);
    }

    static CompletableFuture<WorkerRetirement.Outcome> closeOutcome(
            AutoCloseable session, CompletableFuture<?> terminalOutcome, CloseStarter starter) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(terminalOutcome, "terminalOutcome");
        Objects.requireNonNull(starter, "starter");

        CompletableFuture<Throwable> closeFailure = observe(initiateClose(session, starter));
        CompletableFuture<Void> terminalSettlement = terminalOutcome.handle((ignored, failure) -> null);
        return CompletableFuture.allOf(closeFailure, terminalSettlement)
                .thenApply(ignored -> aggregate(closeFailure.join()));
    }

    private static CompletableFuture<Void> initiateClose(AutoCloseable session, CloseStarter starter) {
        CloseTask task = new CloseTask(session);
        try {
            starter.start(CLOSE_THREAD_PREFIX, task::run);
            return task.completion();
        } catch (RuntimeException | Error failure) {
            Throwable ownerFailure = failure;
            try {
                PoolLifecycleDispatcher.executeRetirementBatch(task::run);
            } catch (RuntimeException | Error fallbackFailure) {
                ownerFailure = FailureAggregation.combine(
                        failure, fallbackFailure, "Worker close owner startup and fallback dispatch both failed");
                task.run();
            }
            return combineFailure(
                    task.completion(), ownerFailure, "Worker close owner startup and fallback close both failed");
        }
    }

    private static CompletableFuture<Void> combineFailure(
            CompletableFuture<Void> completion, Throwable primary, String message) {
        CompletableFuture<Void> combined = new CompletableFuture<>();
        completion.whenComplete((ignored, failure) -> {
            Throwable outcome = failure == null ? primary : FailureAggregation.combine(primary, failure, message);
            combined.completeExceptionally(outcome);
        });
        return combined;
    }

    private static CompletableFuture<Throwable> observe(CompletableFuture<?> future) {
        return future.handle((ignored, failure) -> failure);
    }

    private static WorkerRetirement.Outcome aggregate(Throwable... observedFailures) {
        FailureAccumulator failures = new FailureAccumulator();
        for (Throwable failure : observedFailures) {
            failures.add(failure);
        }
        Throwable failure = failures.aggregateErrorFirst("Multiple failures occurred while closing a pool worker");
        return failure == null ? WorkerRetirement.Outcome.success() : WorkerRetirement.Outcome.failure(failure);
    }

    @FunctionalInterface
    interface CloseStarter {

        Thread start(String threadPrefix, Runnable task);
    }

    private static final class CloseTask {

        private final AutoCloseable session;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicBoolean claimed = new AtomicBoolean();

        private CloseTask(AutoCloseable session) {
            this.session = session;
        }

        private void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                session.close();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }

        private CompletableFuture<Void> completion() {
            return completion;
        }
    }
}
