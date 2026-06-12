/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.CommandService
import io.github.ulviar.procwright.command.CharsetPolicy
import io.github.ulviar.procwright.command.ShutdownPolicy
import io.github.ulviar.procwright.session.LineSession
import io.github.ulviar.procwright.session.ResponseDecoder
import io.github.ulviar.procwright.terminal.TerminalPolicy
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration as KotlinDuration

/** Worker configuration scope for [CommandService.pooledLineSession]. */
@ProcwrightDsl
interface LineWorkerDsl {

    /** Adds one per-worker argument. */
    fun arg(argument: String): LineWorkerDsl

    /** Adds per-worker arguments. */
    fun args(vararg arguments: String): LineWorkerDsl

    /** Adds per-worker arguments. */
    fun args(arguments: Collection<String>): LineWorkerDsl

    /** Sets the per-worker working directory. */
    fun workingDirectory(workingDirectory: Path): LineWorkerDsl

    /** Adds or replaces one per-worker environment override. */
    fun putEnvironment(name: String, value: String): LineWorkerDsl

    /** Inherits the current process environment before applying worker overrides. */
    fun inheritEnvironment(): LineWorkerDsl

    /** Starts workers with only configured environment overrides. */
    fun cleanEnvironment(): LineWorkerDsl

    /** Sets the per-worker shutdown policy. */
    fun shutdown(shutdownPolicy: ShutdownPolicy): LineWorkerDsl

    /** Sets the per-worker idle timeout. */
    fun idleTimeout(timeout: Duration): LineWorkerDsl

    /** Sets the per-worker idle timeout from Kotlin's duration type. */
    fun idleTimeout(timeout: KotlinDuration): LineWorkerDsl

    /** Sets the per-worker terminal policy. */
    fun terminal(terminalPolicy: TerminalPolicy): LineWorkerDsl

    /** Sets the readiness probe run before a worker becomes available. */
    fun readiness(readinessProbe: (LineSession) -> Unit): LineWorkerDsl

    /** Sets the readiness timeout. */
    fun readinessTimeout(timeout: Duration): LineWorkerDsl

    /** Sets the readiness timeout from Kotlin's duration type. */
    fun readinessTimeout(timeout: KotlinDuration): LineWorkerDsl

    /** Sets the default line request timeout. */
    fun requestTimeout(timeout: Duration): LineWorkerDsl

    /** Sets the default line request timeout from Kotlin's duration type. */
    fun requestTimeout(timeout: KotlinDuration): LineWorkerDsl

    /** Sets the retained transcript limit. */
    fun transcriptLimit(limit: Int): LineWorkerDsl

    /** Sets the pending stdout line backlog limit. */
    fun stdoutBacklogLimit(limit: Int): LineWorkerDsl

    /** Sets the maximum single stdout line length. */
    fun maxLineChars(maxLineChars: Int): LineWorkerDsl

    /** Sets the line protocol charset using replacement decoding. */
    fun charset(charset: Charset): LineWorkerDsl

    /** Sets the line protocol charset policy. */
    fun charsetPolicy(charsetPolicy: CharsetPolicy): LineWorkerDsl

    /** Sets the default response decoder. */
    fun responseDecoder(responseDecoder: ResponseDecoder): LineWorkerDsl
}
