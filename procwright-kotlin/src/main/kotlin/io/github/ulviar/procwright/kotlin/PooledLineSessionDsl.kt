/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.session.LineSession
import java.time.Duration
import kotlin.time.Duration as KotlinDuration

/** Pooled line-session configuration scope. */
@ProcwrightDsl
interface PooledLineSessionDsl {

    /** Configures line-session workers used by this pool. */
    fun worker(configure: LineWorkerDsl.() -> Unit): PooledLineSessionDsl

    /** Sets the maximum live worker count. */
    fun maxSize(maxSize: Int): PooledLineSessionDsl

    /** Sets the number of workers opened when the pool is created. */
    fun warmupSize(warmupSize: Int): PooledLineSessionDsl

    /** Sets the minimum idle worker target. */
    fun minIdle(minIdle: Int): PooledLineSessionDsl

    /** Sets the maximum time to wait for an available worker. */
    fun acquireTimeout(timeout: Duration): PooledLineSessionDsl

    /** Sets the maximum time to wait for an available worker from Kotlin's duration type. */
    fun acquireTimeout(timeout: KotlinDuration): PooledLineSessionDsl

    /** Sets the maximum time to wait for one health or reset hook. */
    fun hookTimeout(timeout: Duration): PooledLineSessionDsl

    /** Sets the maximum time to wait for one health or reset hook from Kotlin's duration type. */
    fun hookTimeout(timeout: KotlinDuration): PooledLineSessionDsl

    /** Sets the maximum user requests served by one worker. */
    fun maxRequestsPerWorker(maxRequestsPerWorker: Int): PooledLineSessionDsl

    /** Sets the maximum worker age. */
    fun maxWorkerAge(maxWorkerAge: Duration): PooledLineSessionDsl

    /** Sets the maximum worker age from Kotlin's duration type. */
    fun maxWorkerAge(maxWorkerAge: KotlinDuration): PooledLineSessionDsl

    /** Sets whether retired workers may be replenished in the background. */
    fun backgroundReplenishment(enabled: Boolean): PooledLineSessionDsl

    /** Sets the reset hook run after successful user requests. */
    fun reset(resetHook: (LineSession) -> Unit): PooledLineSessionDsl

    /** Sets the health check run before workers are leased. */
    fun healthCheck(healthCheck: (LineSession) -> Boolean): PooledLineSessionDsl
}
