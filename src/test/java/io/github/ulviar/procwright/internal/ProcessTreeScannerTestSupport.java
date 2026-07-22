/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;

/** Cross-package access to a private scanner instance for internal concurrency tests. */
public final class ProcessTreeScannerTestSupport {

    private ProcessTreeScannerTestSupport() {}

    public static Process guard(Process process, int operationCapacity, Duration providerOperationTimeout) {
        return new ProcessTreeScanner(operationCapacity, 4, Duration.ofMillis(50), providerOperationTimeout)
                .guard(process);
    }
}
