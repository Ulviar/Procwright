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
 *         .withArgs("status", "--short")
 *         .withTimeout(Duration.ofSeconds(10))
 *         .execute();
 *
 * if (!result.succeeded()) {
 *     throw result.toException();
 * }
 * }</pre>
 *
 * <p>Wide behavior is composed from small value objects and policies (capture, shutdown, charset, environment,
 * terminal) rather than low-level flags. Every {@code with*} operation returns a new immutable draft, and terminal
 * operations snapshot that draft once before launching a process.
 */
@NullMarked
package io.github.ulviar.procwright;

import org.jspecify.annotations.NullMarked;
