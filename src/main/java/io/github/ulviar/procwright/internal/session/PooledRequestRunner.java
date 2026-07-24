/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns observation, preparation, acquire, reset, failure classification, and exact-once release for one request. */
final class PooledRequestRunner<S> {

    private final WorkerPoolController<S> pool;
    private final Supplier<WorkerPoolState.Lease<S>> acquire;
    private final Consumer<S> reset;
    private final FailureMapper failureMapper;

    PooledRequestRunner(
            WorkerPoolController<S> pool,
            Supplier<WorkerPoolState.Lease<S>> acquire,
            Consumer<S> reset,
            FailureMapper failureMapper) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.acquire = Objects.requireNonNull(acquire, "acquire");
        this.reset = Objects.requireNonNull(reset, "reset");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
    }

    <R> R run(Function<S, R> request) {
        RequestObservation observation = pool.observeRequest();
        observation.pauseForAcquire();
        return runObserved(observation, request);
    }

    <P, R> R runPrepared(Supplier<P> preparation, BiFunction<S, P, R> request) {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(request, "request");
        RequestObservation observation = pool.observeRequest();
        P prepared;
        try {
            prepared = preparation.get();
        } catch (RuntimeException failure) {
            observation.fail();
            throw map(failure).exception();
        } catch (Error failure) {
            observation.fail();
            throw failure;
        }
        observation.pauseForAcquire();
        return runObserved(observation, session -> request.apply(session, prepared));
    }

    private <R> R runObserved(RequestObservation observation, Function<S, R> request) {
        Objects.requireNonNull(request, "request");
        WorkerPoolState.Lease<S> lease = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        try {
            lease = acquire.get();
            observation.resumeAfterAcquire();
            R response = request.apply(lease.session());
            PooledWorkerRetireReason policyReason = pool.recordRequestAndRetirementReason(lease);
            if (policyReason == null) {
                try {
                    reset.accept(lease.session());
                } catch (RuntimeException resetFailure) {
                    retireReason = PooledWorkerRetireReason.RESET_FAILED;
                    observation.succeed();
                    return response;
                } catch (Error resetFailure) {
                    retireReason = PooledWorkerRetireReason.RESET_FAILED;
                    observation.succeed();
                    throw resetFailure;
                }
                reusable = true;
            } else {
                retireReason = policyReason;
            }
            observation.succeed();
            return response;
        } catch (RuntimeException failure) {
            observation.fail();
            Failure mapped = map(failure);
            retireReason = mapped.retireReason();
            throw mapped.exception();
        } catch (Error failure) {
            observation.fail();
            throw failure;
        } finally {
            if (lease != null) {
                if (reusable) {
                    pool.releaseReusable(lease);
                } else {
                    pool.retire(lease, retireReason);
                }
            }
        }
    }

    private Failure map(RuntimeException failure) {
        return Objects.requireNonNull(failureMapper.map(failure), "failure mapper returned null");
    }

    @FunctionalInterface
    interface FailureMapper {

        Failure map(RuntimeException failure);
    }

    record Failure(PooledWorkerRetireReason retireReason, RuntimeException exception) {

        Failure {
            Objects.requireNonNull(retireReason, "retireReason");
            Objects.requireNonNull(exception, "exception");
        }
    }
}
