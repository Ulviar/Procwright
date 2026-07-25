/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import io.github.ulviar.procwright.internal.ProcessCleanup;
import java.time.Duration;

final class PtyProcessCleanup {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofMillis(250);

    private PtyProcessCleanup() {}

    static void terminate(Process process) {
        if (process == null) {
            return;
        }
        ProcessCleanup.forceStopAndCloseAsync(process, CLEANUP_TIMEOUT);
    }
}
