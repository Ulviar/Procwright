/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Optional integration helpers built on top of the scenario-first Procwright core module.
 */
module io.github.ulviar.procwright.integrations {
    requires transitive io.github.ulviar.procwright;
    requires com.fasterxml.jackson.databind;

    exports io.github.ulviar.procwright.integration;
}
