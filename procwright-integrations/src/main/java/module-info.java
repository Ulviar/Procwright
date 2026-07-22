/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Optional integration helpers built on top of the scenario-first Procwright core module.
 */
module io.github.ulviar.procwright.integrations {
    requires transitive io.github.ulviar.procwright;
    requires transitive com.fasterxml.jackson.databind;
    requires static transitive org.jspecify;

    exports io.github.ulviar.procwright.integration;
}
