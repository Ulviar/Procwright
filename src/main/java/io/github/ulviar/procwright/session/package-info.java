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
 * {@link io.github.ulviar.procwright.session.Expect} automates prompts over a raw session with literal and regex
 * matching.
 *
 * <p>Each session family has an options class with explicit defaults ({@code SessionOptions},
 * {@code LineSessionOptions}, {@code ProtocolSessionOptions}, {@code ExpectOptions}, {@code StreamOptions}, and the
 * pooled variants) and a typed exception with a stable reason enum, so failures can be mapped to domain errors
 * without parsing messages.
 */
package io.github.ulviar.procwright.session;
