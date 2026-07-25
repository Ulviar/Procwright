/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.function.LongSupplier;

final class LineSessionTestDependencies {

    private LineSessionTestDependencies() {}

    static DefaultLineSession.Dependencies withBackoff(ZeroReadBackoff backoff) {
        return new DefaultLineSession.Dependencies(
                backoff,
                PumpStarter.threading(),
                LineSessionTestDependencies::runWriteTask,
                System::nanoTime,
                SerializedRequestGate.Waiter.timed());
    }

    static DefaultLineSession.Dependencies withPumpStarter(PumpStarter pumpStarter) {
        return withBackoffAndPumpStarter(ZeroReadBackoff.exponential(), pumpStarter);
    }

    static DefaultLineSession.Dependencies withBackoffAndPumpStarter(ZeroReadBackoff backoff, PumpStarter pumpStarter) {
        return new DefaultLineSession.Dependencies(
                backoff,
                pumpStarter,
                LineSessionTestDependencies::runWriteTask,
                System::nanoTime,
                SerializedRequestGate.Waiter.timed());
    }

    static DefaultLineSession.Dependencies withTaskRunner(LineRequestWriter.TaskRunner taskRunner) {
        return new DefaultLineSession.Dependencies(
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                taskRunner,
                System::nanoTime,
                SerializedRequestGate.Waiter.timed());
    }

    static DefaultLineSession.Dependencies withNanoTime(LongSupplier nanoTime) {
        return new DefaultLineSession.Dependencies(
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                LineSessionTestDependencies::runWriteTask,
                nanoTime,
                SerializedRequestGate.Waiter.timed());
    }

    static DefaultLineSession.Dependencies withRequestLockWaiter(SerializedRequestGate.Waiter requestLockWaiter) {
        return new DefaultLineSession.Dependencies(
                ZeroReadBackoff.exponential(),
                PumpStarter.threading(),
                LineSessionTestDependencies::runWriteTask,
                System::nanoTime,
                requestLockWaiter);
    }

    private static void runWriteTask(
            BoundedTaskLimiter limiter,
            String threadPrefix,
            long deadlineNanos,
            BoundedTaskHandoff handoff,
            BoundedTaskRunner.Task<Void> task)
            throws java.util.concurrent.TimeoutException, InterruptedException,
                    java.util.concurrent.ExecutionException {
        BoundedTaskRunner.runTracked(limiter, threadPrefix, deadlineNanos, handoff, task);
    }
}
