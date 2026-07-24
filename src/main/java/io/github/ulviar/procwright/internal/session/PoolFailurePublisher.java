/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/** Owns bounded publication and fallback routing of late pool lifecycle failures. */
final class PoolFailurePublisher {

    private final WorkerPoolController.LateFailureReporter reporter;

    PoolFailurePublisher(WorkerPoolController.LateFailureReporter reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    void publishAll(List<FailureReport> reports) {
        Objects.requireNonNull(reports, "reports").forEach(this::publish);
    }

    void publish(FailureReport report) {
        if (report == null) {
            return;
        }
        try {
            PoolLifecycleDispatcher.Ownership ownership = PoolLifecycleDispatcher.report(() -> report(report));
            ownership.started().whenComplete((ignored, launchFailure) -> {
                if (launchFailure != null) {
                    SuppressionSupport.attach(report.failure(), unwrap(launchFailure));
                    BoundedFailureReporter.shared().report(report.failureTarget(), report.failure());
                }
            });
        } catch (RuntimeException | Error dispatchFailure) {
            SuppressionSupport.attach(report.failure(), dispatchFailure);
            BoundedFailureReporter.shared().report(report.failureTarget(), report.failure());
        }
    }

    private void report(FailureReport report) {
        try {
            BoundedFailureReporter.withFailureTarget(
                    report.failureTarget(),
                    () -> reporter.report(BoundedFailureReporter.notificationSourceThread(), report.failure()));
        } catch (RuntimeException | Error reportingFailure) {
            BoundedFailureReporter.shared().report(report.failureTarget(), reportingFailure);
        }
    }

    static void reportBounded(Thread ignored, Throwable failure) {
        BoundedFailureReporter.shared().report(BoundedFailureReporter.captureFailureTarget(), failure);
    }

    static FailureReport capture(Thread sourceThread, Throwable failure) {
        return new FailureReport(BoundedFailureReporter.captureFailureTarget(sourceThread), failure);
    }

    static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException completion && completion.getCause() != null
                ? completion.getCause()
                : failure;
    }
}
