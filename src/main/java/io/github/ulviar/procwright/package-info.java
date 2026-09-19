/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Launches external programs through immutable command configuration and scenario-specific handles.
 *
 * <p>Start with {@link io.github.ulviar.procwright.Procwright#command(String)} and choose an interaction:
 * <ul>
 *   <li>{@link io.github.ulviar.procwright.CommandService#run()} captures the result of one finite command;
 *   <li>{@link io.github.ulviar.procwright.CommandService#listen()} delivers live output chunks;
 *   <li>{@link io.github.ulviar.procwright.CommandService#lineSession()} exchanges line-oriented requests;
 *   <li>{@link io.github.ulviar.procwright.CommandService#protocolSession(java.util.function.Supplier)} uses a typed adapter;
 *   <li>{@link io.github.ulviar.procwright.InteractiveScenario.Entry#expect()} matches prompts and sends replies;
 *   <li>{@link io.github.ulviar.procwright.CommandService#interactive()} exposes raw streams for caller-managed I/O.
 * </ul>
 *
 * <p>Commands and drafts are immutable: retain the value returned by each {@code with*} call. Configuration does not
 * start a process. Each {@code execute()} or {@code open()} starts independent work. Handles returned by {@code open()}
 * must be closed, normally with try-with-resources. A direct line or protocol session already reuses one process;
 * use its pool draft only for concurrent requests to interchangeable workers.
 *
 * <p>Draft documentation gives defaults, limits, and callback concurrency. Handle documentation defines I/O ownership,
 * timeout, failure, and close behavior. Non-zero command exits are results, not automatically exceptions; see
 * {@link io.github.ulviar.procwright.command.CommandResult} and {@link io.github.ulviar.procwright.ProcwrightException}.
 *
 * <p>Unless explicitly annotated {@code @Nullable}, reference parameters and results are non-null. Passing null to
 * a non-null parameter is unsupported. Character limits count UTF-16 code units unless documented otherwise.
 *
 * @see io.github.ulviar.procwright.command
 * @see io.github.ulviar.procwright.session
 * @see io.github.ulviar.procwright.diagnostics
 * @see io.github.ulviar.procwright.terminal
 */
@NullMarked
package io.github.ulviar.procwright;

import org.jspecify.annotations.NullMarked;
