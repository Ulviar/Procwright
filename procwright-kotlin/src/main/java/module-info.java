/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Kotlin conveniences over the Procwright Java API: suspending calls, Kotlin durations, output Flow, and protocol DSL.
 *
 * <p>Named consumer modules can declare {@code requires io.github.ulviar.procwright.kotlin;}; core, Kotlin stdlib,
 * and coroutines are transitive requirements. Extensions keep core draft, result, session, and exception types.
 * They add no process runtime. See the generated Kotlin API for receiver-specific cancellation and ownership rules.
 */
module io.github.ulviar.procwright.kotlin {
    requires transitive io.github.ulviar.procwright;
    requires transitive kotlin.stdlib;
    requires transitive kotlinx.coroutines.core;

    exports io.github.ulviar.procwright.kotlin;
}
