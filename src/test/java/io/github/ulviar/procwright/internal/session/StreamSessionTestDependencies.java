/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.function.LongSupplier;

final class StreamSessionTestDependencies {

    private StreamSessionTestDependencies() {}

    static DefaultStreamSession.Dependencies withBackoffAndPumpStarter(
            ZeroReadBackoff backoff, PumpStarter pumpStarter) {
        return new DefaultStreamSession.Dependencies(backoff, pumpStarter, System::nanoTime);
    }

    static DefaultStreamSession.Dependencies withPumpStarter(PumpStarter pumpStarter) {
        return withBackoffAndPumpStarter(ZeroReadBackoff.exponential(), pumpStarter);
    }

    static DefaultStreamSession.Dependencies withNanoTime(LongSupplier nanoTime) {
        return new DefaultStreamSession.Dependencies(ZeroReadBackoff.exponential(), PumpStarter.threading(), nanoTime);
    }
}
