package io.github.ulviar.icli.testcli;

@FunctionalInterface
interface Scenario {

    int run(ScenarioContext context) throws Exception;
}
