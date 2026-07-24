/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.util.Objects;

record FailureReport(BoundedFailureReporter.FailureTarget failureTarget, Throwable failure) {

    FailureReport {
        Objects.requireNonNull(failureTarget, "failureTarget");
        Objects.requireNonNull(failure, "failure");
    }
}
