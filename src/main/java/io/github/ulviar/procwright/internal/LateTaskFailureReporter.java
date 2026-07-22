/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reports a task failure exactly once when its caller has already abandoned the result. */
public final class LateTaskFailureReporter {

    private final BoundedFailureReporter reporter;
    private final AtomicReference<Thread> taskThread = new AtomicReference<>();
    private final AtomicReference<BoundedFailureReporter.FailureTarget> failureTarget = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean abandoned = new AtomicBoolean();
    private final AtomicBoolean reported = new AtomicBoolean();

    public LateTaskFailureReporter() {
        this(BoundedFailureReporter.shared());
    }

    LateTaskFailureReporter(BoundedFailureReporter reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    /** Binds the reporter before the task invokes provider-controlled code. */
    public void bind(Thread thread) {
        Objects.requireNonNull(thread, "thread");
        if (!taskThread.compareAndSet(null, thread)) {
            throw new IllegalStateException("late task failure reporter is already bound");
        }
        failureTarget.set(BoundedFailureReporter.captureFailureTarget(thread));
    }

    /** Records the task's terminal failure. */
    public void record(Throwable taskFailure) {
        Objects.requireNonNull(taskFailure, "taskFailure");
        failure.compareAndSet(null, taskFailure);
        reportIfReady();
    }

    /** Marks the result as abandoned after caller timeout or interruption. */
    public void abandon() {
        abandoned.set(true);
        reportIfReady();
    }

    private void reportIfReady() {
        Thread thread = taskThread.get();
        BoundedFailureReporter.FailureTarget target = failureTarget.get();
        Throwable taskFailure = failure.get();
        if (thread != null
                && target != null
                && taskFailure != null
                && abandoned.get()
                && reported.compareAndSet(false, true)) {
            reporter.report(target, taskFailure);
        }
    }
}
