/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Optional adapters for repeated request/response exchanges with a CLI process.
 *
 * <p>Start with {@link io.github.ulviar.procwright.integration.ProtocolAdapters}: choose JSON Lines, Content-Length
 * JSON, or delimiter-framed bytes, then pass its factory to
 * {@link io.github.ulviar.procwright.CommandService#protocolSession(java.util.function.Supplier)}. Use
 * {@link io.github.ulviar.procwright.integration.ProtocolAdapters#typedJson(java.util.function.Function,
 * java.util.function.Function, java.util.function.Supplier)} to expose domain types over a JSON transport.
 *
 * <p>Processes, timeouts, request serialization, and cleanup remain owned by core protocol sessions. These adapters
 * provide framing; the child program must already implement the selected wire format. JSON support uses Jackson 3
 * {@link tools.jackson.databind.JsonNode} and UTF-8. References are non-null unless explicitly annotated otherwise.
 */
@NullMarked
package io.github.ulviar.procwright.integration;

import org.jspecify.annotations.NullMarked;
