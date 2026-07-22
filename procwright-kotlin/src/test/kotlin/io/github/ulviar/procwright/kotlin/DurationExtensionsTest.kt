/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.InteractiveScenario
import io.github.ulviar.procwright.LineSessionScenario
import io.github.ulviar.procwright.ProtocolSessionScenario
import io.github.ulviar.procwright.RunScenario
import io.github.ulviar.procwright.StreamScenario
import io.github.ulviar.procwright.session.Expect
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration as JavaDuration
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toJavaDuration

class DurationExtensionsTest {

    @Test
    fun `every draft duration extension forwards the exact Java duration and return type`() {
        val value = 1_234_567_890.nanoseconds

        assertForwarded<RunScenario.Draft>("withTimeout", value) { it.withTimeout(value) }
        assertForwarded<InteractiveScenario.Draft>("withIdleTimeout", value) {
            it.withIdleTimeout(value)
        }
        assertForwarded<InteractiveScenario.Draft>("withReadinessTimeout", value) {
            it.withReadinessTimeout(value)
        }
        assertForwarded<LineSessionScenario.Draft>("withIdleTimeout", value) {
            it.withIdleTimeout(value)
        }
        assertForwarded<LineSessionScenario.Draft>("withReadinessTimeout", value) {
            it.withReadinessTimeout(value)
        }
        assertForwarded<LineSessionScenario.Draft>("withRequestTimeout", value) {
            it.withRequestTimeout(value)
        }
        assertForwarded<LineSessionScenario.PoolDraft>("withAcquireTimeout", value) {
            it.withAcquireTimeout(value)
        }
        assertForwarded<LineSessionScenario.PoolDraft>("withHookTimeout", value) {
            it.withHookTimeout(value)
        }
        assertForwarded<LineSessionScenario.PoolDraft>("withMaxWorkerAge", value) {
            it.withMaxWorkerAge(value)
        }
        assertForwarded<StreamScenario.Draft>("withTimeout", value) { it.withTimeout(value) }
        assertForwarded<ProtocolSessionScenario.Draft<String, String>>("withIdleTimeout", value) {
            it.withIdleTimeout(value)
        }
        assertForwarded<ProtocolSessionScenario.Draft<String, String>>(
            "withReadinessTimeout",
            value,
        ) {
            it.withReadinessTimeout(value)
        }
        assertForwarded<ProtocolSessionScenario.Draft<String, String>>(
            "withRequestTimeout",
            value,
        ) {
            it.withRequestTimeout(value)
        }
        assertForwarded<ProtocolSessionScenario.PoolDraft<String, String>>(
            "withAcquireTimeout",
            value,
        ) {
            it.withAcquireTimeout(value)
        }
        assertForwarded<ProtocolSessionScenario.PoolDraft<String, String>>(
            "withHookTimeout",
            value,
        ) {
            it.withHookTimeout(value)
        }
        assertForwarded<ProtocolSessionScenario.PoolDraft<String, String>>(
            "withMaxWorkerAge",
            value,
        ) {
            it.withMaxWorkerAge(value)
        }
        assertForwarded<Expect.Draft>("withTimeout", value) { it.withTimeout(value) }
    }

    @Test
    fun `draft duration extensions inherit Java zero negative and infinite validation`() {
        javaService().interactive().withArgs(*fixtureArgs("sleep", "30000")).open().use { session ->
            val service = javaService()
            val protocol = service.protocolSession(lineAdapterFactory())
            val nonNegative =
                listOf<(Duration) -> Any>(
                    { service.run().withTimeout(it) },
                    { service.interactive().withIdleTimeout(it) },
                    { service.lineSession().withIdleTimeout(it) },
                    { service.lineSession().pooled().withMaxWorkerAge(it) },
                    { service.listen().withTimeout(it) },
                    { protocol.withIdleTimeout(it) },
                    { protocol.pooled().withMaxWorkerAge(it) },
                )
            val positive =
                listOf<(Duration) -> Any>(
                    { service.interactive().withReadinessTimeout(it) },
                    { service.lineSession().withReadinessTimeout(it) },
                    { service.lineSession().withRequestTimeout(it) },
                    { service.lineSession().pooled().withAcquireTimeout(it) },
                    { service.lineSession().pooled().withHookTimeout(it) },
                    { service.lineSession().pooled().withCloseTimeout(it) },
                    { protocol.withReadinessTimeout(it) },
                    { protocol.withRequestTimeout(it) },
                    { protocol.pooled().withAcquireTimeout(it) },
                    { protocol.pooled().withHookTimeout(it) },
                    { protocol.pooled().withCloseTimeout(it) },
                    { session.expect().withTimeout(it) },
                )

            nonNegative.forEach { extension ->
                extension(Duration.ZERO)
                extension(INFINITE)
                assertFailsWith<IllegalArgumentException> { extension((-1).nanoseconds) }
            }
            positive.forEach { extension ->
                extension(INFINITE)
                assertFailsWith<IllegalArgumentException> { extension(Duration.ZERO) }
                assertFailsWith<IllegalArgumentException> { extension((-1).nanoseconds) }
            }
        }
    }

    @Test
    fun `session and expect duration extensions inherit Java validation`() {
        javaService().lineSession().withArgs(*fixtureArgs("line-repl")).open().use { line ->
            assertEquals("response:line", line.request("line", INFINITE).text())
            assertFailsWith<IllegalArgumentException> { line.request("zero", Duration.ZERO) }
            assertFailsWith<IllegalArgumentException> { line.request("negative", (-1).nanoseconds) }
        }

        javaService()
            .protocolSession(lineAdapterFactory())
            .withArgs(*fixtureArgs("line-repl"))
            .open()
            .use { protocol ->
                assertEquals("response:protocol", protocol.request("protocol", INFINITE))
                assertFailsWith<IllegalArgumentException> {
                    protocol.request("zero", Duration.ZERO)
                }
                assertFailsWith<IllegalArgumentException> {
                    protocol.request("negative", (-1).nanoseconds)
                }
            }

        javaService()
            .lineSession()
            .withArgs(*fixtureArgs("line-repl"))
            .pooled()
            .withMinIdle(0)
            .open()
            .use { linePool ->
                assertEquals("response:pool", linePool.request("pool", INFINITE).text())
                assertFailsWith<IllegalArgumentException> {
                    linePool.request("zero", Duration.ZERO)
                }
                assertFailsWith<IllegalArgumentException> {
                    linePool.request("negative", (-1).nanoseconds)
                }
            }

        javaService()
            .protocolSession(lineAdapterFactory())
            .withArgs(*fixtureArgs("line-repl"))
            .pooled()
            .withMinIdle(0)
            .open()
            .use { protocolPool ->
                assertEquals("response:pool", protocolPool.request("pool", INFINITE))
                assertFailsWith<IllegalArgumentException> {
                    protocolPool.request("zero", Duration.ZERO)
                }
                assertFailsWith<IllegalArgumentException> {
                    protocolPool.request("negative", (-1).nanoseconds)
                }
            }

        javaService().interactive().withArgs(*fixtureArgs("expect-output")).open().use { session ->
            session.expect().open().use { expect ->
                expect.expectText("text", INFINITE)
                expect.expectRegex(Pattern.compile("regex42"), INFINITE)
                assertEquals("match", expect.expectTextMatch("match", INFINITE).matched())
                assertEquals(
                    "regex99",
                    expect.expectRegexMatch(Pattern.compile("regex99"), INFINITE).matched(),
                )
                assertFailsWith<IllegalArgumentException> {
                    expect.expectText("never", Duration.ZERO)
                }
                assertFailsWith<IllegalArgumentException> {
                    expect.expectRegex(Pattern.compile("never"), (-1).nanoseconds)
                }
                assertFailsWith<IllegalArgumentException> {
                    expect.expectTextMatch("never", Duration.ZERO)
                }
                assertFailsWith<IllegalArgumentException> {
                    expect.expectRegexMatch(Pattern.compile("never"), (-1).nanoseconds)
                }
            }
        }
    }

    private inline fun <reified T : Any> assertForwarded(
        expectedMethod: String,
        duration: Duration,
        invoke: (T) -> T,
    ) {
        val recorder = DurationRecorder(T::class.java)

        val returned = invoke(recorder.proxy)

        assertTrue(returned === recorder.proxy)
        assertEquals(
            listOf(RecordedCall(expectedMethod, duration.toJavaDuration())),
            recorder.calls,
        )
    }

    private data class RecordedCall(val method: String, val duration: JavaDuration)

    private class DurationRecorder<T : Any>(type: Class<T>) : InvocationHandler {
        val calls = mutableListOf<RecordedCall>()
        val proxy: T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), this))

        override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === arguments?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "DurationRecorder"
                    else -> error("Unexpected Object method: ${method.name}")
                }
            }
            val duration =
                arguments?.singleOrNull() as? JavaDuration
                    ?: error("Expected one java.time.Duration argument for ${method.name}")
            calls += RecordedCall(method.name, duration)
            return proxy
        }
    }
}
