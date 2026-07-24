/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;

/** Port through which mandatory close cleanup submits best-effort notifications. */
interface CloseNotificationPublisher {

    void execute(Thread sourceThread, Runnable callback);

    void report(Thread sourceThread, Throwable failure);

    static CloseNotificationPublisher using(BoundedFailureReporter reporter) {
        Objects.requireNonNull(reporter, "reporter");
        return new CloseNotificationPublisher() {
            @Override
            public void execute(Thread sourceThread, Runnable callback) {
                reporter.execute(sourceThread, callback);
            }

            @Override
            public void report(Thread sourceThread, Throwable failure) {
                reporter.report(sourceThread, failure);
            }
        };
    }
}
