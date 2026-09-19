/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Process execution, live output, prompt automation, and request/response sessions for Java 25 applications.
 *
 * <p>The core uses only the JDK at runtime. Start with {@link io.github.ulviar.procwright.Procwright}; optional
 * Kotlin extensions and ready-made JSON/framing adapters are provided by separate modules. Application code should
 * use the exported packages; internal process management is not an extension API.
 */
module io.github.ulviar.procwright {
    requires static transitive org.jspecify;

    exports io.github.ulviar.procwright;
    exports io.github.ulviar.procwright.command;
    exports io.github.ulviar.procwright.diagnostics;
    exports io.github.ulviar.procwright.session;
    exports io.github.ulviar.procwright.terminal;
}
