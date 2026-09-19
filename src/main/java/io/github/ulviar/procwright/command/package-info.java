/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Command model and run policies: what to launch and how a one-shot run behaves.
 *
 * <p>{@link io.github.ulviar.procwright.command.CommandSpec} describes the immutable base command (direct argv by
 * default, explicit shell mode on request). Scenario drafts carry launch and behavior choices without exposing
 * mutable builders or options carriers. Behavior decisions are explicit policy values:
 * {@link io.github.ulviar.procwright.command.CapturePolicy} (bounded in-memory capture,
 * discard, or redirect to files), {@link io.github.ulviar.procwright.command.ShutdownPolicy} (interrupt-then-kill
 * escalation), {@link io.github.ulviar.procwright.command.CharsetPolicy} (forgiving or strict decoding),
 * {@link io.github.ulviar.procwright.command.OutputMode}, and
 * {@link io.github.ulviar.procwright.command.EnvironmentPolicy}.
 *
 * <p>A finished run is a {@link io.github.ulviar.procwright.command.CommandResult}: exit code, captured output,
 * truncation flags, timeout flag, and elapsed time. Non-zero exits stay results;
 * {@link io.github.ulviar.procwright.command.CommandExecutionException} is reserved for launch, supervision, or
 * capture failures. Decode failures retain a diagnostic result in the exception, including the captured bytes.
 *
 * <p>Specifications, policies, inputs, and results are immutable and reusable; file-backed values retain paths rather
 * than snapshots of filesystem contents. Unless explicitly marked {@code @Nullable}, reference parameters and return
 * values are non-null; passing {@code null} is unsupported. Collection elements and map entries are also non-null.
 */
@NullMarked
package io.github.ulviar.procwright.command;

import org.jspecify.annotations.NullMarked;
