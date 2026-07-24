/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Owns post-monitor retirement batches, close observation, and late failure publication. */
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

    Batch<S> newBatch() {
        return new Batch<>(this);
    }

    private void dispatch(List<PoolWorker<S>> workers) {
        if (workers.isEmpty()) {
            return;
        }
        Runnable retirement = () -> run(workers);
        try {
            dispatcher.accept(retirement);
        } catch (RuntimeException | Error dispatchFailure) {
            retirement.run();
            throw dispatchFailure;
        }
    }

    private void run(List<PoolWorker<S>> workers) {
        workers.forEach(PoolWorker::initiateClose);
        List<FailureReport> immediateReports = new ArrayList<>();
        for (PoolWorker<S> worker : workers) {
            try {
                observe(worker, immediateReports);
            } catch (RuntimeException | Error failure) {
                unexpectedFailure.accept(worker, failure);
            }
        }
        immediateReports.forEach(reporter);
    }

    private void observe(PoolWorker<S> worker, List<FailureReport> immediateReports) {
        CompletableFuture<WorkerRetirement.Outcome> closeOutcome = worker.closeOutcome();
        if (closeOutcome.isDone()) {
            FailureReport report = completion.apply(worker, closeOutcome.join());
            if (report != null) {
                immediateReports.add(report);
            }
            return;
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
    }

    static final class Batch<S> {

        private final WorkerRetirementCoordinator<S> owner;
        private final List<PoolWorker<S>> workers = new ArrayList<>();
        private boolean dispatched;

        private Batch(WorkerRetirementCoordinator<S> owner) {
            this.owner = owner;
        }

        void add(PoolWorker<S> worker) {
            if (dispatched) {
                throw new IllegalStateException("retirement batch is already dispatched");
            }
            workers.add(Objects.requireNonNull(worker, "worker"));
        }

        void dispatch() {
            if (dispatched) {
                return;
            }
            dispatched = true;
            owner.dispatch(List.copyOf(workers));
        }
    }
}
