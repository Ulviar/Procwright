/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Submits late pool failures without making best-effort reporting part of lifecycle completion. */
final class PoolFailurePublisher {

    private final Consumer<FailureReport> submit;

    /** The supplied sink must submit notifications without waiting for their delivery. */
    PoolFailurePublisher(Consumer<FailureReport> submit) {
        this.submit = Objects.requireNonNull(submit, "submit");
    }

    void publishAll(List<FailureReport> reports) {
        Objects.requireNonNull(reports, "reports").forEach(this::publish);
    }

    void publish(FailureReport report) {
        if (report == null) {
            return;
        }
        try {
            submit.accept(report);
        } catch (RuntimeException | Error ignored) {
            // Lifecycle accounting has settled; unavailable reporting must not replace its outcome.
        }
    }

    static void reportBounded(FailureReport report) {
        BoundedFailureReporter.shared().report(report.failureTarget(), report.failure());
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
