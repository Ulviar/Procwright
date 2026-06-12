/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Command model and run policies: what to launch and how a one-shot run behaves.
 *
 * <p>{@link io.github.ulviar.procwright.command.CommandSpec} describes the base command (direct argv by default,
 * explicit shell mode on request), {@link io.github.ulviar.procwright.command.CommandInvocation} carries per-call
 * overrides, and {@link io.github.ulviar.procwright.command.RunOptions} bundles the run defaults. Behavior decisions
 * are explicit policy values: {@link io.github.ulviar.procwright.command.CapturePolicy} (bounded in-memory capture,
 * discard, or redirect to files), {@link io.github.ulviar.procwright.command.ShutdownPolicy} (interrupt-then-kill
 * escalation), {@link io.github.ulviar.procwright.command.CharsetPolicy} (forgiving or strict decoding),
 * {@link io.github.ulviar.procwright.command.OutputMode}, and
 * {@link io.github.ulviar.procwright.command.EnvironmentPolicy}.
 *
 * <p>A finished run is a {@link io.github.ulviar.procwright.command.CommandResult}: exit code, captured output,
 * truncation flags, timeout flag, and elapsed time. Non-zero exits stay results;
 * {@link io.github.ulviar.procwright.command.CommandExecutionException} is reserved for launch, supervision, or
 * capture failures where no normal result could be produced.
 */
package io.github.ulviar.procwright.command;
