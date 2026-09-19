/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Long-lived process sessions: raw interactive access, line and typed protocols, pooling, streaming, and
 * expect-style automation.
 *
 * <p>{@link io.github.ulviar.procwright.session.Session} is the raw handle with stdin/stdout/stderr access and
 * lifecycle observation. {@link io.github.ulviar.procwright.session.LineSession} serializes line-oriented
 * request/response exchanges; {@link io.github.ulviar.procwright.session.ProtocolSession} does the same for typed
 * requests through a caller-provided {@link io.github.ulviar.procwright.session.ProtocolAdapter}. Both have pooled
 * variants ({@link io.github.ulviar.procwright.session.PooledLineSession},
 * {@link io.github.ulviar.procwright.session.PooledProtocolSession}) that reuse worker processes.
 * {@link io.github.ulviar.procwright.session.StreamSession} delivers listen-only output chunks, and
 * {@link io.github.ulviar.procwright.session.Expect} automates prompts in its own pre-launch output mode with literal
 * and regex matching.
 *
 * <p>Open handles through {@link io.github.ulviar.procwright.CommandService}; immutable scenario drafts configure
 * future processes and may be reused. Live handles own resources: use try-with-resources, including after natural
 * process exit. Concurrent requests are serialized within one line or protocol session; pools distribute independent
 * requests across workers without affinity.
 *
 * <p>Completion futures are independent views: cancelling or completing a view does not stop its process. Close the
 * owning handle to request shutdown. Protocol adapter, response decoder, and stream listener callbacks run on
 * Procwright threads and should cooperate with interruption. Callback-specific thread confinement is documented on
 * {@link ProtocolReader}, {@link ProtocolWriter},
 * {@link ResponseDecoder}, and {@link StreamListener}.
 *
 * <p>Reference values are non-null unless explicitly marked otherwise. Transcript and result records are immutable
 * snapshots; transcript limits measure UTF-16 code units and do not make process output safe to log. Exceptions expose
 * stable reason enums, but retryability follows the operation contract rather than the reason alone.
 */
@NullMarked
package io.github.ulviar.procwright.session;

import org.jspecify.annotations.NullMarked;
