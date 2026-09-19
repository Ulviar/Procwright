/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.ExpectScenario
import io.github.ulviar.procwright.InteractiveScenario
import io.github.ulviar.procwright.LineSessionScenario
import io.github.ulviar.procwright.ProtocolSessionScenario
import io.github.ulviar.procwright.RunScenario
import io.github.ulviar.procwright.StreamScenario
import io.github.ulviar.procwright.session.Expect
import io.github.ulviar.procwright.session.ExpectMatch
import io.github.ulviar.procwright.session.LineResponse
import io.github.ulviar.procwright.session.LineSession
import io.github.ulviar.procwright.session.PooledLineSession
import io.github.ulviar.procwright.session.PooledProtocolSession
import io.github.ulviar.procwright.session.ProtocolSession
import java.util.regex.Pattern
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Returns an immutable draft with its execution timeout set from a Kotlin duration.
 *
 * Bounds a finite execution. A timeout stops its process under the configured shutdown policy;
 * cleanup may take additional time. Zero disables the execution deadline. See
 * [RunScenario.Draft.withTimeout] for the core contract.
 *
 * @param timeout non-negative duration; zero disables this limit
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is negative
 */
fun RunScenario.Draft.withTimeout(timeout: Duration): RunScenario.Draft =
    withTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its idle timeout set from a Kotlin duration.
 *
 * Bounds inactivity as defined by the interactive session. Zero disables idle shutdown. See
 * [InteractiveScenario.Draft.withIdleTimeout] for the core contract.
 *
 * @param timeout non-negative duration; zero disables this limit
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is negative
 */
fun InteractiveScenario.Draft.withIdleTimeout(timeout: Duration): InteractiveScenario.Draft =
    withIdleTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its readiness timeout set from a Kotlin duration.
 *
 * Bounds the configured readiness probe during open. Expiration fails startup and closes the newly
 * started session; interruption cannot forcibly stop arbitrary probe code. See
 * [InteractiveScenario.Draft.withReadinessTimeout] for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun InteractiveScenario.Draft.withReadinessTimeout(timeout: Duration): InteractiveScenario.Draft =
    withReadinessTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its idle timeout set from a Kotlin duration.
 *
 * Bounds caller-visible session inactivity. Background draining for diagnostics does not keep the
 * session alive. Zero disables idle shutdown. See [LineSessionScenario.Draft.withIdleTimeout] for
 * the core contract.
 *
 * @param timeout non-negative duration; zero disables this limit
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is negative
 */
fun LineSessionScenario.Draft.withIdleTimeout(timeout: Duration): LineSessionScenario.Draft =
    withIdleTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its readiness timeout set from a Kotlin duration.
 *
 * Bounds the readiness probe before open returns its usable line session. Expiration fails startup
 * and closes that session. See [LineSessionScenario.Draft.withReadinessTimeout] for the core
 * contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun LineSessionScenario.Draft.withReadinessTimeout(timeout: Duration): LineSessionScenario.Draft =
    withReadinessTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its default request timeout set from a Kotlin duration.
 *
 * Bounds each line request, including local preparation, waiting for an earlier request, writing,
 * and response decoding. Per-call request timeouts override this default. See
 * [LineSessionScenario.Draft.withRequestTimeout] for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun LineSessionScenario.Draft.withRequestTimeout(timeout: Duration): LineSessionScenario.Draft =
    withRequestTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its worker acquisition timeout set from a Kotlin duration.
 *
 * Bounds waiting for a usable worker, including startup and health checks. The subsequent request
 * and reset hook have separate budgets. See [LineSessionScenario.PoolDraft.withAcquireTimeout] for
 * the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun LineSessionScenario.PoolDraft.withAcquireTimeout(
    timeout: Duration
): LineSessionScenario.PoolDraft = withAcquireTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its worker-hook timeout set from a Kotlin duration.
 *
 * Bounds each health or reset hook. A health check is additionally limited by the remaining
 * acquisition budget. Expiration retires the affected worker. See
 * [LineSessionScenario.PoolDraft.withHookTimeout] for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun LineSessionScenario.PoolDraft.withHookTimeout(
    timeout: Duration
): LineSessionScenario.PoolDraft = withHookTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its synchronous close timeout set from a Kotlin duration.
 *
 * Bounds how long close waits for the pool to drain. Expiration fails that wait without cancelling
 * cleanup; [PooledLineSession.closeAsync] can observe eventual logical completion. See
 * [LineSessionScenario.PoolDraft.withCloseTimeout] for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun LineSessionScenario.PoolDraft.withCloseTimeout(
    timeout: Duration
): LineSessionScenario.PoolDraft = withCloseTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its maximum worker age set from a Kotlin duration.
 *
 * Retires aged workers at lifecycle boundaries, not in the middle of a healthy active request. Zero
 * disables age-based retirement. See [LineSessionScenario.PoolDraft.withMaxWorkerAge] for the core
 * contract.
 *
 * @param age non-negative duration; zero disables this limit
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if age is negative
 */
fun LineSessionScenario.PoolDraft.withMaxWorkerAge(age: Duration): LineSessionScenario.PoolDraft =
    withMaxWorkerAge(age.toJavaDuration())

/**
 * Returns an immutable draft with its absolute stream timeout set from a Kotlin duration.
 *
 * Bounds the stream lifetime even while output is arriving. Expiration closes the process; cleanup
 * may take additional time. Zero disables this deadline. See [StreamScenario.Draft.withTimeout] for
 * the core contract.
 *
 * @param timeout non-negative duration; zero disables this limit
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is negative
 */
fun StreamScenario.Draft.withTimeout(timeout: Duration): StreamScenario.Draft =
    withTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its idle timeout set from a Kotlin duration.
 *
 * Bounds caller-visible session inactivity. Background draining for diagnostics does not keep the
 * session alive. Zero disables idle shutdown. See [ProtocolSessionScenario.Draft.withIdleTimeout]
 * for the core contract.
 *
 * @param timeout non-negative duration; zero disables this limit
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is negative
 */
fun <I : Any, O : Any> ProtocolSessionScenario.Draft<I, O>.withIdleTimeout(
    timeout: Duration
): ProtocolSessionScenario.Draft<I, O> = withIdleTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its readiness timeout set from a Kotlin duration.
 *
 * Bounds the readiness probe before open returns its usable protocol session. Expiration fails
 * startup and closes that session. See [ProtocolSessionScenario.Draft.withReadinessTimeout] for the
 * core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun <I : Any, O : Any> ProtocolSessionScenario.Draft<I, O>.withReadinessTimeout(
    timeout: Duration
): ProtocolSessionScenario.Draft<I, O> = withReadinessTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its default request timeout set from a Kotlin duration.
 *
 * Bounds waiting for the serialized request slot and running both adapter callbacks. A timeout
 * after acquiring the slot closes the session. Per-call request timeouts override this default. See
 * [ProtocolSessionScenario.Draft.withRequestTimeout] for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun <I : Any, O : Any> ProtocolSessionScenario.Draft<I, O>.withRequestTimeout(
    timeout: Duration
): ProtocolSessionScenario.Draft<I, O> = withRequestTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its worker acquisition timeout set from a Kotlin duration.
 *
 * Bounds waiting for a usable worker, including startup and health checks. The subsequent request
 * and reset hook have separate budgets. See [ProtocolSessionScenario.PoolDraft.withAcquireTimeout]
 * for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun <I : Any, O : Any> ProtocolSessionScenario.PoolDraft<I, O>.withAcquireTimeout(
    timeout: Duration
): ProtocolSessionScenario.PoolDraft<I, O> = withAcquireTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its worker-hook timeout set from a Kotlin duration.
 *
 * Bounds each health or reset hook. A health check is additionally limited by the remaining
 * acquisition budget. Expiration retires the affected worker. See
 * [ProtocolSessionScenario.PoolDraft.withHookTimeout] for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun <I : Any, O : Any> ProtocolSessionScenario.PoolDraft<I, O>.withHookTimeout(
    timeout: Duration
): ProtocolSessionScenario.PoolDraft<I, O> = withHookTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its synchronous close timeout set from a Kotlin duration.
 *
 * Bounds how long close waits for the pool to drain. Expiration fails that wait without cancelling
 * cleanup; [PooledProtocolSession.closeAsync] can observe eventual logical completion. See
 * [ProtocolSessionScenario.PoolDraft.withCloseTimeout] for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun <I : Any, O : Any> ProtocolSessionScenario.PoolDraft<I, O>.withCloseTimeout(
    timeout: Duration
): ProtocolSessionScenario.PoolDraft<I, O> = withCloseTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its maximum worker age set from a Kotlin duration.
 *
 * Retires aged workers at lifecycle boundaries, not in the middle of a healthy active request. Zero
 * disables age-based retirement. See [ProtocolSessionScenario.PoolDraft.withMaxWorkerAge] for the
 * core contract.
 *
 * @param age non-negative duration; zero disables this limit
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if age is negative
 */
fun <I : Any, O : Any> ProtocolSessionScenario.PoolDraft<I, O>.withMaxWorkerAge(
    age: Duration
): ProtocolSessionScenario.PoolDraft<I, O> = withMaxWorkerAge(age.toJavaDuration())

/**
 * Returns an immutable draft with its default prompt-match timeout set from a Kotlin duration.
 *
 * Bounds each match independently, including waiting behind another matcher. It does not bound the
 * process lifetime or a complete sequence of prompts. See [ExpectScenario.Draft.withTimeout] for
 * the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun ExpectScenario.Draft.withTimeout(timeout: Duration): ExpectScenario.Draft =
    withTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its process idle timeout set from a Kotlin duration.
 *
 * Bounds caller-visible session inactivity independently of each match deadline. Zero disables idle
 * shutdown. See [ExpectScenario.Draft.withIdleTimeout] for the core contract.
 *
 * @param timeout non-negative duration; zero disables this limit
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is negative
 */
fun ExpectScenario.Draft.withIdleTimeout(timeout: Duration): ExpectScenario.Draft =
    withIdleTimeout(timeout.toJavaDuration())

/**
 * Returns an immutable draft with its readiness timeout set from a Kotlin duration.
 *
 * Bounds the readiness probe before open returns its usable Expect handle. Expiration fails startup
 * and closes that handle. See [ExpectScenario.Draft.withReadinessTimeout] for the core contract.
 *
 * @param timeout positive duration
 * @return an updated draft; the receiver is unchanged
 * @throws IllegalArgumentException if timeout is zero or negative
 */
fun ExpectScenario.Draft.withReadinessTimeout(timeout: Duration): ExpectScenario.Draft =
    withReadinessTimeout(timeout.toJavaDuration())

/**
 * Performs a blocking line request using a Kotlin duration.
 *
 * The budget includes request preparation, waiting for an earlier request, and the exchange.
 * Failures before stdin handoff can leave the session reusable; failures after handoff close it.
 * See [LineSession] for details. Use [requestAwait] inside a coroutine to avoid blocking its
 * thread.
 *
 * @param line request text without CR or LF; the session appends LF
 * @param timeout positive request budget; zero does not disable it
 * @return the response produced by the line decoder
 * @throws IllegalArgumentException if timeout is zero or negative, or line contains CR or LF
 * @throws io.github.ulviar.procwright.session.LineSessionException if the exchange cannot complete
 */
fun LineSession.request(line: String, timeout: Duration): LineResponse =
    request(line, timeout.toJavaDuration())

/**
 * Performs a blocking typed protocol request using a Kotlin duration.
 *
 * The budget includes waiting for the request slot and both adapter callbacks. A timeout while
 * waiting leaves the session reusable; a timeout after acquiring the slot closes it. See
 * [ProtocolSession.request] for details. Use [requestAwait] inside a coroutine to avoid blocking
 * its thread.
 *
 * @param request non-null input for the adapter; do not mutate it during the call
 * @param timeout positive request budget; zero does not disable it
 * @return the non-null response produced by the protocol adapter
 * @throws IllegalArgumentException if timeout is zero or negative
 * @throws io.github.ulviar.procwright.session.ProtocolSessionException if the exchange cannot
 *   complete
 */
fun <I : Any, O : Any> ProtocolSession<I, O>.request(request: I, timeout: Duration): O =
    request(request, timeout.toJavaDuration())

/**
 * Performs a blocking pooled line request using a Kotlin duration.
 *
 * This timeout bounds request work, not worker acquisition or the reset hook. Those use the pool
 * settings. Failed worker exchanges retire their worker. See [PooledLineSession] for details. Use
 * [requestAwait] inside a coroutine to avoid blocking its thread.
 *
 * @param line request text without CR or LF; the session appends LF
 * @param timeout positive request budget; zero does not disable it
 * @return the response produced by the line decoder
 * @throws IllegalArgumentException if timeout is zero or negative, or line contains CR or LF
 * @throws io.github.ulviar.procwright.session.PooledSessionException if worker acquisition,
 *   startup, or health checking fails
 * @throws io.github.ulviar.procwright.session.LineSessionException if the worker exchange fails
 */
fun PooledLineSession.request(line: String, timeout: Duration): LineResponse =
    request(line, timeout.toJavaDuration())

/**
 * Performs a blocking pooled typed protocol request using a Kotlin duration.
 *
 * This timeout bounds request work, not worker acquisition or the reset hook. Those use the pool
 * settings. Failed worker exchanges retire their worker. See [PooledProtocolSession.request] for
 * details. Use [requestAwait] inside a coroutine to avoid blocking its thread.
 *
 * @param request non-null input for the adapter; do not mutate it during the call
 * @param timeout positive request budget; zero does not disable it
 * @return the non-null response produced by the protocol adapter
 * @throws IllegalArgumentException if timeout is zero or negative
 * @throws io.github.ulviar.procwright.session.PooledSessionException if worker acquisition,
 *   startup, or health checking fails
 * @throws io.github.ulviar.procwright.session.ProtocolSessionException if the worker exchange fails
 */
fun <I : Any, O : Any> PooledProtocolSession<I, O>.request(request: I, timeout: Duration): O =
    request(request, timeout.toJavaDuration())

/**
 * Blocks until decoded stdout contains the literal text, using a Kotlin duration.
 *
 * The budget includes waiting behind another matcher. Matching consumes buffered output through the
 * matched text; stderr is diagnostic output and is not searched. A timeout while waiting for output
 * or another matcher leaves the handle open for another match. See [Expect] for failure and buffer
 * semantics.
 *
 * @param text literal text to find in stdout
 * @param timeout positive match budget; zero does not disable it
 * @return this same Expect handle for another operation
 * @throws IllegalArgumentException if timeout is zero or negative
 * @throws io.github.ulviar.procwright.session.ExpectException if matching times out, output ends,
 *   the handle closes, or process I/O or matching fails
 */
fun Expect.expectText(text: String, timeout: Duration): Expect =
    expectText(text, timeout.toJavaDuration())

/**
 * Blocks until decoded stdout contains a match for the pattern, using a Kotlin duration.
 *
 * The budget includes waiting behind another matcher. Matching consumes buffered output through the
 * end of the match; stderr is diagnostic output and is not searched. A timeout while waiting for
 * output is retryable, but abandoning an in-progress regex evaluation closes the handle. The
 * TIMEOUT reason alone does not imply that retry is safe. See [Expect.expectRegex] and [Expect] for
 * failure and buffer semantics.
 *
 * @param pattern regular expression searched with find semantics
 * @param timeout positive match budget; zero does not disable it
 * @return this same Expect handle for another operation
 * @throws IllegalArgumentException if timeout is zero or negative
 * @throws io.github.ulviar.procwright.session.ExpectException if matching times out, output ends,
 *   the handle closes, or process I/O or matching fails
 */
fun Expect.expectRegex(pattern: Pattern, timeout: Duration): Expect =
    expectRegex(pattern, timeout.toJavaDuration())

/**
 * Blocks until decoded stdout contains the literal text, using a Kotlin duration.
 *
 * The budget includes waiting behind another matcher. Matching consumes buffered output through the
 * matched text; stderr is diagnostic output and is not searched. A timeout while waiting for output
 * or another matcher leaves the handle open for another match. See [Expect] for failure and buffer
 * semantics.
 *
 * @param text literal text to find in stdout
 * @param timeout positive match budget; zero does not disable it
 * @return match data including consumed prefix and the matched literal; output is not redacted
 * @throws IllegalArgumentException if timeout is zero or negative
 * @throws io.github.ulviar.procwright.session.ExpectException if matching times out, output ends,
 *   the handle closes, or process I/O or matching fails
 */
fun Expect.expectTextMatch(text: String, timeout: Duration): ExpectMatch =
    expectTextMatch(text, timeout.toJavaDuration())

/**
 * Blocks until decoded stdout contains a match for the pattern, using a Kotlin duration.
 *
 * The budget includes waiting behind another matcher. Matching consumes buffered output through the
 * end of the match; stderr is diagnostic output and is not searched. A timeout while waiting for
 * output is retryable, but abandoning an in-progress regex evaluation closes the handle. The
 * TIMEOUT reason alone does not imply that retry is safe. See [Expect.expectRegexMatch] and
 * [Expect] for failure and buffer semantics.
 *
 * @param pattern regular expression searched with find semantics
 * @param timeout positive match budget; zero does not disable it
 * @return match data including consumed prefix and capture groups; output is not redacted
 * @throws IllegalArgumentException if timeout is zero or negative
 * @throws io.github.ulviar.procwright.session.ExpectException if matching times out, output ends,
 *   the handle closes, or process I/O or matching fails
 */
fun Expect.expectRegexMatch(pattern: Pattern, timeout: Duration): ExpectMatch =
    expectRegexMatch(pattern, timeout.toJavaDuration())
