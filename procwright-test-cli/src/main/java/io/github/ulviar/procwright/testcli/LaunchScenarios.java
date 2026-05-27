package io.github.ulviar.procwright.testcli;

import java.io.IOException;

final class LaunchScenarios {

    private LaunchScenarios() {}

    static int argvEnvCwd(ScenarioContext context) throws IOException {
        context.stdoutLine("cwd:" + context.workingDirectory().normalize());
        for (String name : context.options().values("env")) {
            context.stdoutLine("env:" + name + "=" + context.environment().getOrDefault(name, "<missing>"));
        }
        context.stdoutLine("argv:" + String.join("|", context.options().positionals()));
        return context.options().integer("exit-code", 0);
    }

    static int terminalCheck(ScenarioContext context) throws IOException {
        boolean present = System.console() != null;
        context.stdoutLine("terminal:" + (present ? "present" : "missing"));
        if (present) {
            return context.options().integer("exit-code", 0);
        }
        return context.options().integer("failure-exit-code", 64);
    }
}
