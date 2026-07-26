/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.Objects;

/** Maps shared pool lifecycle failures to the public pooled-session taxonomy. */
final class PooledSessionFailures implements WorkerPoolController.FailureFactory, PoolCloseSupport.FailureFactory {

    private final String scenario;

    PooledSessionFailures(String scenario) {
        this.scenario = Objects.requireNonNull(scenario, "scenario");
    }

    static PooledWorkerRetireReason retireReason(PooledSessionException failure) {
        return failure.reason() == PooledSessionException.Reason.HOOK_TIMEOUT
                ? PooledWorkerRetireReason.TIMEOUT
                : PooledWorkerRetireReason.WORKER_FAILED;
    }

    PooledSessionException hookTimeout(String message) {
        return failure(PooledSessionException.Reason.HOOK_TIMEOUT, message);
    }

    PooledSessionException interrupted(String message, InterruptedException cause) {
        return failure(PooledSessionException.Reason.INTERRUPTED, message, cause);
    }

    PooledSessionException workerFailure(String message, Throwable cause) {
        return failure(PooledSessionException.Reason.WORKER_FAILED, message, cause);
    }

    @Override
    public RuntimeException closed(String message) {
        return failure(PooledSessionException.Reason.CLOSED, "Pooled " + scenario + " session is closed");
    }

    @Override
    public RuntimeException acquireTimeout(String message) {
        return failure(PooledSessionException.Reason.ACQUIRE_TIMEOUT, message);
    }

    @Override
    public RuntimeException acquireInterrupted(String message, InterruptedException cause) {
        return interrupted(message, cause);
    }

    @Override
    public RuntimeException startupFailed(String message, Throwable cause) {
        return failure(PooledSessionException.Reason.STARTUP_FAILED, message, cause);
    }

    @Override
    public RuntimeException retirementFailed(String message, Throwable cause) {
        return workerFailure(message, cause);
    }

    @Override
    public Throwable exposeAggregate(RuntimeException primary, Throwable aggregate) {
        if (primary instanceof PooledSessionException failure) {
            return failure(failure.reason(), failure.getMessage(), aggregate);
        }
        return aggregate;
    }

    @Override
    public RuntimeException drainTimeout(Duration timeout) {
        return failure(
                PooledSessionException.Reason.DRAIN_TIMEOUT,
                "Pooled " + scenario + " session did not drain within " + timeout);
    }

    @Override
    public RuntimeException interrupted(InterruptedException cause) {
        return failure(
                PooledSessionException.Reason.INTERRUPTED,
                "Interrupted while closing pooled " + scenario + " session",
                cause);
    }

    @Override
    public RuntimeException workerFailed(Throwable cause) {
        return workerFailure("Pooled " + scenario + "-session worker cleanup failed", cause);
    }

    private static PooledSessionException failure(PooledSessionException.Reason reason, String message) {
        return new PooledSessionException(reason, message);
    }

    private static PooledSessionException failure(
            PooledSessionException.Reason reason, String message, Throwable cause) {
        return new PooledSessionException(reason, message, cause);
    }
}
