/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Scenario-first process control: pick a scenario, configure it with policies, and get typed results.
 *
 * <p>{@link io.github.ulviar.procwright.Procwright} and {@link io.github.ulviar.procwright.CommandService} are the
 * entry points. A service binds one base command to scenario defaults and exposes the scenario catalog:
 *
 * <ul>
 *   <li>{@code run()} — a finite process with a typed {@code CommandResult} (exit code, output, timeout,
 *       truncation);
 *   <li>{@code interactive()} — a raw long-lived session with stdin/stdout/stderr access and expect-style helpers;
 *   <li>{@code lineSession()} — serialized line-oriented request/response over stdin/stdout, optionally pooled;
 *   <li>{@code protocolSession(...)} — typed request/response through a caller-provided protocol adapter, optionally
 *       pooled;
 *   <li>{@code listen()} — listen-only streaming of process output chunks.
 * </ul>
 *
 * <pre>{@code
 * CommandResult result = Procwright.command("git")
 *         .run()
 *         .withTimeout(Duration.ofSeconds(10))
 *         .execute("status", "--short");
 *
 * if (!result.succeeded()) {
 *     throw result.toException();
 * }
 * }</pre>
 *
 * <p>Wide behavior is composed from small value objects and policies (capture, shutdown, charset, environment,
 * terminal) rather than low-level flags; invalid combinations are rejected when options are built or resolved, before
 * a process starts.
 */
package io.github.ulviar.procwright;
