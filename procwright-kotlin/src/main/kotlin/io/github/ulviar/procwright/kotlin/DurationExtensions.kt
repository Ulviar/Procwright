/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

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

/** Returns a run draft with its execution timeout set from a Kotlin duration. */
fun RunScenario.Draft.withTimeout(timeout: Duration): RunScenario.Draft =
    withTimeout(timeout.toJavaDuration())

/** Returns an interactive draft with its idle timeout set from a Kotlin duration. */
fun InteractiveScenario.Draft.withIdleTimeout(timeout: Duration): InteractiveScenario.Draft =
    withIdleTimeout(timeout.toJavaDuration())

/** Returns an interactive draft with its readiness timeout set from a Kotlin duration. */
fun InteractiveScenario.Draft.withReadinessTimeout(timeout: Duration): InteractiveScenario.Draft =
    withReadinessTimeout(timeout.toJavaDuration())

/** Returns a line-session draft with its idle timeout set from a Kotlin duration. */
fun LineSessionScenario.Draft.withIdleTimeout(timeout: Duration): LineSessionScenario.Draft =
    withIdleTimeout(timeout.toJavaDuration())

/** Returns a line-session draft with its readiness timeout set from a Kotlin duration. */
fun LineSessionScenario.Draft.withReadinessTimeout(timeout: Duration): LineSessionScenario.Draft =
    withReadinessTimeout(timeout.toJavaDuration())

/** Returns a line-session draft with its request timeout set from a Kotlin duration. */
fun LineSessionScenario.Draft.withRequestTimeout(timeout: Duration): LineSessionScenario.Draft =
    withRequestTimeout(timeout.toJavaDuration())

/** Returns a line-pool draft with its acquisition timeout set from a Kotlin duration. */
fun LineSessionScenario.PoolDraft.withAcquireTimeout(
    timeout: Duration
): LineSessionScenario.PoolDraft = withAcquireTimeout(timeout.toJavaDuration())

/** Returns a line-pool draft with its worker-hook timeout set from a Kotlin duration. */
fun LineSessionScenario.PoolDraft.withHookTimeout(
    timeout: Duration
): LineSessionScenario.PoolDraft = withHookTimeout(timeout.toJavaDuration())

/** Returns a line-pool draft with its synchronous close timeout set from a Kotlin duration. */
fun LineSessionScenario.PoolDraft.withCloseTimeout(
    timeout: Duration
): LineSessionScenario.PoolDraft = withCloseTimeout(timeout.toJavaDuration())

/** Returns a line-pool draft with its maximum worker age set from a Kotlin duration. */
fun LineSessionScenario.PoolDraft.withMaxWorkerAge(age: Duration): LineSessionScenario.PoolDraft =
    withMaxWorkerAge(age.toJavaDuration())

/** Returns a stream draft with its absolute timeout set from a Kotlin duration. */
fun StreamScenario.Draft.withTimeout(timeout: Duration): StreamScenario.Draft =
    withTimeout(timeout.toJavaDuration())

/** Returns a protocol-session draft with its idle timeout set from a Kotlin duration. */
fun <I : Any, O : Any> ProtocolSessionScenario.Draft<I, O>.withIdleTimeout(
    timeout: Duration
): ProtocolSessionScenario.Draft<I, O> = withIdleTimeout(timeout.toJavaDuration())

/** Returns a protocol-session draft with its readiness timeout set from a Kotlin duration. */
fun <I : Any, O : Any> ProtocolSessionScenario.Draft<I, O>.withReadinessTimeout(
    timeout: Duration
): ProtocolSessionScenario.Draft<I, O> = withReadinessTimeout(timeout.toJavaDuration())

/** Returns a protocol-session draft with its request timeout set from a Kotlin duration. */
fun <I : Any, O : Any> ProtocolSessionScenario.Draft<I, O>.withRequestTimeout(
    timeout: Duration
): ProtocolSessionScenario.Draft<I, O> = withRequestTimeout(timeout.toJavaDuration())

/** Returns a protocol-pool draft with its acquisition timeout set from a Kotlin duration. */
fun <I : Any, O : Any> ProtocolSessionScenario.PoolDraft<I, O>.withAcquireTimeout(
    timeout: Duration
): ProtocolSessionScenario.PoolDraft<I, O> = withAcquireTimeout(timeout.toJavaDuration())

/** Returns a protocol-pool draft with its worker-hook timeout set from a Kotlin duration. */
fun <I : Any, O : Any> ProtocolSessionScenario.PoolDraft<I, O>.withHookTimeout(
    timeout: Duration
): ProtocolSessionScenario.PoolDraft<I, O> = withHookTimeout(timeout.toJavaDuration())

/** Returns a protocol-pool draft with its synchronous close timeout set from a Kotlin duration. */
fun <I : Any, O : Any> ProtocolSessionScenario.PoolDraft<I, O>.withCloseTimeout(
    timeout: Duration
): ProtocolSessionScenario.PoolDraft<I, O> = withCloseTimeout(timeout.toJavaDuration())

/** Returns a protocol-pool draft with its maximum worker age set from a Kotlin duration. */
fun <I : Any, O : Any> ProtocolSessionScenario.PoolDraft<I, O>.withMaxWorkerAge(
    age: Duration
): ProtocolSessionScenario.PoolDraft<I, O> = withMaxWorkerAge(age.toJavaDuration())

/** Returns an expect draft with its default match timeout set from a Kotlin duration. */
fun Expect.Draft.withTimeout(timeout: Duration): Expect.Draft =
    withTimeout(timeout.toJavaDuration())

/** Performs a line-session request with a Kotlin duration timeout. */
fun LineSession.request(line: String, timeout: Duration): LineResponse =
    request(line, timeout.toJavaDuration())

/** Performs a typed protocol request with a Kotlin duration timeout. */
fun <I : Any, O : Any> ProtocolSession<I, O>.request(request: I, timeout: Duration): O =
    request(request, timeout.toJavaDuration())

/** Performs a pooled line request with a Kotlin duration timeout. */
fun PooledLineSession.request(line: String, timeout: Duration): LineResponse =
    request(line, timeout.toJavaDuration())

/** Performs a pooled typed protocol request with a Kotlin duration timeout. */
fun <I : Any, O : Any> PooledProtocolSession<I, O>.request(request: I, timeout: Duration): O =
    request(request, timeout.toJavaDuration())

/** Waits for literal text for at most the Kotlin duration and returns this expect helper. */
fun Expect.expectText(text: String, timeout: Duration): Expect =
    expectText(text, timeout.toJavaDuration())

/**
 * Waits for a regular expression for at most the Kotlin duration and returns this expect helper.
 */
fun Expect.expectRegex(pattern: Pattern, timeout: Duration): Expect =
    expectRegex(pattern, timeout.toJavaDuration())

/** Waits for literal text for at most the Kotlin duration and returns its match. */
fun Expect.expectTextMatch(text: String, timeout: Duration): ExpectMatch =
    expectTextMatch(text, timeout.toJavaDuration())

/** Waits for a regular expression for at most the Kotlin duration and returns its match. */
fun Expect.expectRegexMatch(pattern: Pattern, timeout: Duration): ExpectMatch =
    expectRegexMatch(pattern, timeout.toJavaDuration())
