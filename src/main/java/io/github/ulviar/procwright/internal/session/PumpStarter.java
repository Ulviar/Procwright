/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;

/** Starts a pump asynchronously; implementations must not run the task inline. */
@FunctionalInterface
interface PumpStarter {

    Thread start(String namePrefix, Runnable task);

    static PumpStarter threading() {
        return Threading::start;
    }
}
