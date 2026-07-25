/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Owns post-monitor retirement batches, close completion, and late failure publication. */
final class WorkerRetirementCoordinator<S> {

    private final Consumer<Runnable> dispatcher;
    private final BiFunction<PoolWorker<S>, WorkerRetirement.Outcome, FailureReport> completion;
    private final BiConsumer<PoolWorker<S>, Throwable> unexpectedFailure;
    private final Consumer<FailureReport> reporter;

    WorkerRetirementCoordinator(
            Consumer<Runnable> dispatcher,
            BiFunction<PoolWorker<S>, WorkerRetirement.Outcome, FailureReport> completion,
            BiConsumer<PoolWorker<S>, Throwable> unexpectedFailure,
            Consumer<FailureReport> reporter) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.unexpectedFailure = Objects.requireNonNull(unexpectedFailure, "unexpectedFailure");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    void dispatch(List<PoolWorker<S>> workers) {
        List<PoolWorker<S>> batch = List.copyOf(Objects.requireNonNull(workers, "workers"));
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("retirement batch must not be empty");
        }
        Runnable retirement = () -> run(batch);
        try {
            dispatcher.accept(retirement);
        } catch (RuntimeException | Error dispatchFailure) {
            retirement.run();
            throw dispatchFailure;
        }
    }

    private void run(List<PoolWorker<S>> workers) {
        for (PoolWorker<S> worker : workers) {
            worker.initiateClose();
        }
        List<FailureReport> immediateReports = new ArrayList<>(workers.size());
        for (PoolWorker<S> worker : workers) {
            FailureReport report = observeSafely(worker);
            if (report != null) {
                immediateReports.add(report);
            }
        }
        immediateReports.forEach(reporter);
    }

    private FailureReport observeSafely(PoolWorker<S> worker) {
        try {
            return observe(worker);
        } catch (RuntimeException | Error failure) {
            unexpectedFailure.accept(worker, failure);
            return null;
        }
    }

    private FailureReport observe(PoolWorker<S> worker) {
        CompletableFuture<WorkerRetirement.Outcome> closeOutcome = worker.closeOutcome();
        if (closeOutcome.isDone()) {
            return completion.apply(worker, closeOutcome.join());
        }
        closeOutcome.whenComplete((outcome, failure) -> {
            if (failure != null) {
                unexpectedFailure.accept(worker, PoolFailurePublisher.unwrap(failure));
                return;
            }
            try {
                reporter.accept(completion.apply(worker, outcome));
            } catch (RuntimeException | Error completionFailure) {
                unexpectedFailure.accept(worker, completionFailure);
            }
        });
        return null;
    }
}
