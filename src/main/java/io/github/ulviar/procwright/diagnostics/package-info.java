/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Best-effort lifecycle event hooks for command scenarios.
 *
 * <p>Scenario draft methods attach a {@link io.github.ulviar.procwright.diagnostics.DiagnosticListener} for structured
 * {@link io.github.ulviar.procwright.diagnostics.DiagnosticEvent}s (process prepared, started, exited, timeout,
 * truncation) and a {@link io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink} for storing the same kind
 * of events. Neither callback receives process output. Use scenario results, output listeners, or session diagnostic
 * snapshots for stdout/stderr. Runtime-generated command echoes omit argument and environment values.
 *
 * <p>Diagnostics never change execution behavior: delivery is decoupled from the runtime, and the defaults are
 * no-ops. Delivery may drop events under pressure, is not flushed by command/session completion, and may overlap across
 * independent lifecycles. Listener and transcript-sink choices belong to each immutable scenario draft; caller-owned
 * recipients and their storage are not closed by Procwright.
 *
 * <p>Unless explicitly marked {@code @Nullable}, reference parameters and return values are non-null; passing
 * {@code null} is unsupported. Collection elements and map entries are also non-null.
 */
@NullMarked
package io.github.ulviar.procwright.diagnostics;

import org.jspecify.annotations.NullMarked;
