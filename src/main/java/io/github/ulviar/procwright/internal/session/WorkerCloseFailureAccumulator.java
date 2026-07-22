/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.util.Objects;

/** Owns deterministic failure precedence for one worker-close aggregation. */
final class WorkerCloseFailureAccumulator {

    private Throwable primary;

    synchronized void add(Throwable failure) {
        if (failure == null || failure == primary) {
            return;
        }
        Objects.requireNonNull(failure, "failure");
        if (primary == null) {
            primary = failure;
            return;
        }
        if (!(primary instanceof Error) && failure instanceof Error) {
            SuppressionSupport.attach(failure, primary);
            primary = failure;
            return;
        }
        SuppressionSupport.attach(primary, failure);
    }

    synchronized Throwable failure() {
        return primary;
    }
}
