package io.github.ulviar.icli.testcli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class LifecycleScenarios {

    private LifecycleScenarios() {}

    static int exit(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        context.sleepMillis(options.longValue("startup-delay-millis", 0));
        context.stdoutText(options.string("stdout", ""));
        context.stderrText(options.string("stderr", ""));
        context.sleepMillis(options.longValue("exit-delay-millis", 0));
        return options.integer("exit-code", 0);
    }

    static int sleep(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        if (options.bool("started", true)) {
            context.stdoutLine(options.string("started-text", "started"));
        }
        context.sleepMillis(options.longValue("millis", 1000));
        if (options.bool("finished", true)) {
            context.stdoutLine(options.string("finished-text", "finished"));
        }
        return options.integer("exit-code", 0);
    }

    static int neverExit(ScenarioContext context) throws Exception {
        context.stdoutLine(context.options().string("started-text", "started"));
        while (true) {
            Thread.sleep(60_000);
        }
    }

    static int shutdownHook(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        OutputStream stdout = context.stdout();
        long hookDelayMillis = options.longValue("hook-delay-millis", 1000);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> runShutdownHook(context, stdout, hookDelayMillis), "icli-test-cli-shutdown-hook"));

        context.stdoutLine(options.string("started-text", "started"));
        long runMillis = options.longValue("run-millis", -1);
        if (runMillis < 0) {
            while (true) {
                Thread.sleep(60_000);
            }
        }
        context.sleepMillis(runMillis);
        return options.integer("exit-code", 0);
    }

    static int spawnChild(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        String childScenario = options.string("child-scenario", "sleep");
        List<String> command = testCliCommand(childScenario);
        if ("sleep".equals(childScenario)) {
            command.add("--millis=" + options.longValue("child-millis", 1000));
        } else if ("never-exit".equals(childScenario)) {
            command.add("--started-text=child-started");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        context.stdoutLine("child:" + child.pid());
        if (options.bool("wait", false)) {
            context.stdoutLine("child-exit:" + child.waitFor());
        }
        return options.integer("exit-code", 0);
    }

    static int spawnTree(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        String leafScenario = options.string("leaf-scenario", "sleep");
        List<String> command = testCliCommand("spawn-child");
        command.add("--child-scenario=" + leafScenario);
        command.add("--wait=true");
        if ("sleep".equals(leafScenario)) {
            command.add("--child-millis=" + options.longValue("leaf-millis", 1000));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        context.stdoutLine("child:" + child.pid());

        BufferedReader childStdout =
                new BufferedReader(new InputStreamReader(child.getInputStream(), context.charset()));
        String firstChildLine = childStdout.readLine();
        if (firstChildLine != null && firstChildLine.startsWith("child:")) {
            context.stdoutLine("grandchild:" + firstChildLine.substring("child:".length()));
        }

        if (options.bool("wait", false)) {
            boolean exited = child.waitFor(options.longValue("wait-millis", 5000), TimeUnit.MILLISECONDS);
            if (exited) {
                context.stdoutLine("child-exit:" + child.exitValue());
            } else {
                child.destroyForcibly();
                context.stdoutLine("child-timeout");
            }
        }
        return options.integer("exit-code", 0);
    }

    static int repeatSpawn(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        int count = options.integer("count", 1);
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        for (int index = 0; index < count; index++) {
            List<String> command = testCliCommand(options.string("child-scenario", "exit"));
            command.addAll(options.values("child-arg"));
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean exited = process.waitFor(options.longValue("child-timeout-millis", 5000), TimeUnit.MILLISECONDS);
            if (exited) {
                context.stdoutLine("iteration:" + index + ":exit:" + process.exitValue());
            } else {
                process.destroyForcibly();
                context.stdoutLine("iteration:" + index + ":timeout");
                if (options.bool("fail-fast", true)) {
                    return options.integer("timeout-exit-code", 124);
                }
            }
        }
        return options.integer("exit-code", 0);
    }

    private static void runShutdownHook(ScenarioContext context, OutputStream stdout, long hookDelayMillis) {
        try {
            stdout.write("shutdown-hook:start\n".getBytes(context.charset()));
            stdout.flush();
            Thread.sleep(hookDelayMillis);
            stdout.write("shutdown-hook:end\n".getBytes(context.charset()));
            stdout.flush();
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> testCliCommand(String scenario) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TestCli.class.getName());
        command.add(scenario);
        return command;
    }

    private static String javaExecutable() {
        String executableName =
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }
}
