/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

final class ProtocolSessionTestDependencies {

    private ProtocolSessionTestDependencies() {}

    static DefaultProtocolSession.Dependencies withBackoff(ZeroReadBackoff backoff) {
        return new DefaultProtocolSession.Dependencies(
                backoff,
                PumpStarter.threading(),
                System::nanoTime,
                TimedTaskRunner::runCancellable,
                SerializedRequestGate.Waiter.timed());
    }

    static DefaultProtocolSession.Dependencies withPumpStarter(PumpStarter pumpStarter) {
        return withBackoffAndPumpStarter(ZeroReadBackoff.exponential(), pumpStarter);
    }

    static DefaultProtocolSession.Dependencies withBackoffAndPumpStarter(
            ZeroReadBackoff backoff, PumpStarter pumpStarter) {
        return new DefaultProtocolSession.Dependencies(
                backoff,
                pumpStarter,
                System::nanoTime,
                TimedTaskRunner::runCancellable,
                SerializedRequestGate.Waiter.timed());
    }

    static DefaultProtocolSession.Dependencies withCallbackRunner(
            DefaultProtocolSession.ProtocolCallbackRunner callbackRunner) {
        return new DefaultProtocolSession.Dependencies(
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                System::nanoTime,
                callbackRunner,
                SerializedRequestGate.Waiter.timed());
    }

    static DefaultProtocolSession.Dependencies withRequestLockWaiter(SerializedRequestGate.Waiter requestLockWaiter) {
        return new DefaultProtocolSession.Dependencies(
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                System::nanoTime,
                TimedTaskRunner::runCancellable,
                requestLockWaiter);
    }
}
