package io.github.ulviar.procwright.testcli;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

final class TestCliApplication {

    private TestCliApplication() {}

    static int run(
            String[] args,
            InputStream stdin,
            OutputStream stdout,
            OutputStream stderr,
            Map<String, String> environment,
            Path workingDirectory)
            throws Exception {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(workingDirectory, "workingDirectory");

        CliOptions options = CliOptions.parse(args);
        Scenario scenario = ScenarioRegistry.find(options.scenario());
        ScenarioContext context = new ScenarioContext(options, stdin, stdout, stderr, environment, workingDirectory);
        return scenario.run(context);
    }
}
