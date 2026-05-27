package io.github.ulviar.procwright.testcli;

@FunctionalInterface
interface Scenario {

    int run(ScenarioContext context) throws Exception;
}
