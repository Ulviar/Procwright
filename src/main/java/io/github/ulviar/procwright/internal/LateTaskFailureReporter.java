/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Submits at most one best-effort failure notification after a caller abandons a task's result. */
final class LateTaskFailureReporter {

    private final BoundedFailureReporter reporter;
    private final AtomicReference<BoundedFailureReporter.FailureTarget> failureTarget = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean abandoned = new AtomicBoolean();
    private final AtomicBoolean reported = new AtomicBoolean();

    LateTaskFailureReporter() {
        this(BoundedFailureReporter.shared());
    }

    LateTaskFailureReporter(BoundedFailureReporter reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    /** Binds the reporter before the task invokes provider-controlled code. */
    void bind(Thread thread) {
        BoundedFailureReporter.FailureTarget target =
                BoundedFailureReporter.captureFailureTarget(Objects.requireNonNull(thread, "thread"));
        if (!failureTarget.compareAndSet(null, target)) {
            throw new IllegalStateException("late task failure reporter is already bound");
        }
    }

    /** Records the task's terminal failure. */
    void record(Throwable taskFailure) {
        Objects.requireNonNull(taskFailure, "taskFailure");
        failure.compareAndSet(null, taskFailure);
        reportIfReady();
    }

    /** Marks the result as abandoned after caller timeout or interruption. */
    void abandon() {
        abandoned.set(true);
        reportIfReady();
    }

    private void reportIfReady() {
        BoundedFailureReporter.FailureTarget target = failureTarget.get();
        Throwable taskFailure = failure.get();
        if (target != null && taskFailure != null && abandoned.get() && reported.compareAndSet(false, true)) {
            reporter.report(target, taskFailure);
        }
    }
}
