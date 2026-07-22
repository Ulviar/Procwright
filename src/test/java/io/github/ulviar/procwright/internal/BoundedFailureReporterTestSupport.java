/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;

/** Cross-package access to failure-reporting settlement for internal concurrency tests. */
public final class BoundedFailureReporterTestSupport {

    private BoundedFailureReporterTestSupport() {}

    public static boolean awaitSharedSettlement(Duration timeout) throws InterruptedException {
        return BoundedFailureReporter.shared().awaitSettlement(timeout);
    }
}
