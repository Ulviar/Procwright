/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Observability hooks for command scenarios: lifecycle events and bounded transcripts.
 *
 * <p>Scenario draft methods attach a {@link io.github.ulviar.procwright.diagnostics.DiagnosticListener} for structured
 * {@link io.github.ulviar.procwright.diagnostics.DiagnosticEvent}s (process prepared, started, exited, timeout,
 * truncation) and a {@link io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink} for transcript capture.
 * Command echoes are redacted by default so argument values do not leak into logs.
 *
 * <p>Diagnostics never change execution behavior: delivery is decoupled from the runtime, and the defaults are
 * no-ops. Listener and transcript-sink choices belong to each immutable scenario draft.
 */
@NullMarked
package io.github.ulviar.procwright.diagnostics;

import org.jspecify.annotations.NullMarked;
