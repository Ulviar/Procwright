/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

/** Identifies the signal and interruption policy of one process-tree shutdown phase. */
enum ShutdownPhase {
    GRACEFUL,
    FORCEFUL;

    boolean isForceful() {
        return this == FORCEFUL;
    }
}
