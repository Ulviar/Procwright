/* SPDX-License-Identifier: Apache-2.0 */

/**
 * JSON and byte-framing adapters for Procwright protocol sessions.
 *
 * <p>The entry point is {@link io.github.ulviar.procwright.integration.ProtocolAdapters}. This module transitively
 * requires Procwright core and Jackson databind, so named consumer modules need only
 * {@code requires io.github.ulviar.procwright.integrations;} to use these adapters. It adds no process runtime;
 * sessions and pools retain the lifecycle and concurrency contracts of core.
 */
module io.github.ulviar.procwright.integrations {
    requires transitive io.github.ulviar.procwright;
    requires transitive tools.jackson.databind;
    requires static transitive org.jspecify;

    exports io.github.ulviar.procwright.integration;
}
