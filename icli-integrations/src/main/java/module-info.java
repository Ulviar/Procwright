/**
 * Optional integration helpers built on top of the scenario-first iCLI core module.
 */
module io.github.ulviar.icli.integrations {
    requires transitive io.github.ulviar.icli;
    requires com.fasterxml.jackson.databind;

    exports io.github.ulviar.icli.integration;
}
