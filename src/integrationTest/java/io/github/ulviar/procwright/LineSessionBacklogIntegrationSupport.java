/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.session.LineSession;
import java.time.Duration;

abstract class LineSessionBacklogIntegrationSupport extends LineSessionIntegrationSupport {

    static boolean eventuallyTranscriptTruncated(LineSession session) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (session.transcript().truncated()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
