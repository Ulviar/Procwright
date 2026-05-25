package io.github.ulviar.icli.testcli;

import java.util.Random;

final class NondeterministicScenarios {

    private NondeterministicScenarios() {}

    static int flaky(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        Random random = new Random(options.longValue("seed", 1));
        int draw = random.nextInt(100);
        int hangPercent = options.integer("hang-percent", 0);
        int failPercent = options.integer("fail-percent", 25);
        if (draw < hangPercent) {
            return LifecycleScenarios.neverExit(context);
        }
        context.sleepMillis(random.nextLong(Math.max(1, options.longValue("max-delay-millis", 1) + 1)));
        if (draw < hangPercent + failPercent) {
            context.stderrLine("flaky:failed:" + draw);
            return options.integer("failure-exit-code", 75);
        }
        context.stdoutLine("flaky:ok:" + draw);
        return options.integer("exit-code", 0);
    }
}
