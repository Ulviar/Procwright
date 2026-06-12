/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.CommandService
import io.github.ulviar.procwright.command.ShutdownPolicy
import java.nio.file.Path
import java.time.Duration

/**
 * Receiver used by [CommandService.listenFlow].
 *
 * This adapter intentionally does not expose the low-level output listener setter. Flow owns chunk
 * delivery and preserves the core listen scenario backpressure semantics.
 */
@ProcwrightDsl
interface ListenFlowInvocation {

    /** Adds one per-stream argument. */
    fun arg(argument: String): ListenFlowInvocation

    /** Adds per-stream arguments. */
    fun args(vararg arguments: String): ListenFlowInvocation

    /** Adds per-stream arguments. */
    fun args(arguments: Collection<String>): ListenFlowInvocation

    /** Sets the per-stream working directory. */
    fun workingDirectory(workingDirectory: Path): ListenFlowInvocation

    /** Adds or replaces one per-stream environment override. */
    fun putEnvironment(name: String, value: String): ListenFlowInvocation

    /** Sets the per-stream shutdown policy override. */
    fun shutdown(shutdownPolicy: ShutdownPolicy): ListenFlowInvocation

    /** Sets the per-stream absolute timeout override. */
    fun timeout(timeout: Duration): ListenFlowInvocation

    /** Keeps stdin open after the stream starts. */
    fun keepStdinOpen(): ListenFlowInvocation

    /** Closes stdin immediately after the stream starts. */
    fun closeStdinOnStart(): ListenFlowInvocation
}
