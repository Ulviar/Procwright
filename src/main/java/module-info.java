/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Scenario-first command execution and interactive process workflows for JVM applications.
 */
module io.github.ulviar.procwright {
    requires static transitive org.jspecify;

    exports io.github.ulviar.procwright;
    exports io.github.ulviar.procwright.command;
    exports io.github.ulviar.procwright.diagnostics;
    exports io.github.ulviar.procwright.preset;
    exports io.github.ulviar.procwright.session;
    exports io.github.ulviar.procwright.terminal;
}
