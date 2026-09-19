/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Owns post-monitor retirement batches, close completion, and late failure publication. */
final class WorkerRetirementCoordinator<S> {

    private final Dispatcher dispatcher;
    private final BiFunction<PoolWorker<S>, WorkerRetirement.Outcome, FailureReport> completion;
    private final BiConsumer<PoolWorker<S>, Throwable> unexpectedFailure;
    private final Consumer<FailureReport> reporter;

    WorkerRetirementCoordinator(
            Dispatcher dispatcher,
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
        batch.forEach(PoolWorker::initiateClose);
        Runnable outcomeProcessing = () -> batch.forEach(this::observe);
        try {
            dispatcher.dispatch(outcomeProcessing);
        } catch (RuntimeException | Error dispatchFailure) {
            outcomeProcessing.run();
            throw dispatchFailure;
        }
    }

    private void observe(PoolWorker<S> worker) {
        try {
            worker.closeOutcome().thenAccept(outcome -> complete(worker, outcome));
        } catch (RuntimeException | Error failure) {
            unexpectedFailure.accept(worker, failure);
        }
    }

    private void complete(PoolWorker<S> worker, WorkerRetirement.Outcome outcome) {
        try {
            FailureReport report = completion.apply(worker, outcome);
            if (report != null) {
                reporter.accept(report);
            }
        } catch (RuntimeException | Error failure) {
            unexpectedFailure.accept(worker, failure);
        }
    }

    @FunctionalInterface
    interface Dispatcher {

        /** Accepts the task or throws before the task can run. */
        void dispatch(Runnable task);
    }
}
