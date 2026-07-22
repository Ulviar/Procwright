/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.PooledLineSession;

public final class PoolDrainTimeoutExample {

    private PoolDrainTimeoutExample() {}

    public static LineResponse requestAndClose(LineSessionScenario.PoolDraft draft, String request) {
        PooledLineSession pool = draft.open();
        try (pool) {
            return pool.request(request);
        } finally {
            pool.closeAsync().whenComplete((ignored, cleanupFailure) -> {
                if (cleanupFailure != null) {
                    cleanupFailure.printStackTrace(System.err);
                }
            });
        }
    }
}
