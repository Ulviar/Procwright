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

        CompletableFuture<WorkerRetirement.Outcome> close = initiateClose(session, starter);
        CompletableFuture<Void> terminalSettlement = terminalOutcome.handle((ignored, failure) -> null);
        return close.thenCombine(terminalSettlement, (outcome, ignored) -> outcome);
    }

    private static CompletableFuture<WorkerRetirement.Outcome> initiateClose(
            AutoCloseable session, CloseStarter starter) {
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

    private static CompletableFuture<WorkerRetirement.Outcome> combineFailure(
            CompletableFuture<WorkerRetirement.Outcome> completion, Throwable primary, String message) {
        return completion.thenApply(outcome -> {
            FailureAccumulator failures = new FailureAccumulator();
            failures.add(primary);
            failures.add(outcome.failure());
            return WorkerRetirement.Outcome.failure(failures.aggregateErrorFirst(message));
        });
    }

    @FunctionalInterface
    interface CloseStarter {

        Thread start(String threadPrefix, Runnable task);
    }

    private static final class CloseTask {

        private final AutoCloseable session;
        private final CompletableFuture<WorkerRetirement.Outcome> completion = new CompletableFuture<>();
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
                completion.complete(WorkerRetirement.Outcome.success());
            } catch (Throwable failure) {
                completion.complete(WorkerRetirement.Outcome.failure(failure));
            }
        }

        private CompletableFuture<WorkerRetirement.Outcome> completion() {
            return completion;
        }
    }
}
