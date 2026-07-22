/* SPDX-License-Identifier: Apache-2.0 */

/** Kotlin coroutine and duration extensions for Procwright. */
module io.github.ulviar.procwright.kotlin {
    requires transitive io.github.ulviar.procwright;
    requires transitive kotlin.stdlib;
    requires transitive kotlinx.coroutines.core;

    exports io.github.ulviar.procwright.kotlin;
}
