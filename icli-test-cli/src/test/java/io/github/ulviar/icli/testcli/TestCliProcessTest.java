package io.github.ulviar.icli.testcli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TestCliProcessTest {

    @Test
    void mainPropagatesScenarioExitCode() throws Exception {
        ProcessRun run = runProcess("", "exit", "--stdout=done\n", "--stderr=problem\n", "--exit-code=23");

        assertEquals(23, run.exitCode());
        assertEquals("done\n", run.stdout());
        assertEquals("problem\n", run.stderr());
    }

    @Test
    void spawnChildScenarioExposesChildPidAndCanWaitForChild() throws Exception {
        ProcessRun run = runProcess("", "spawn-child", "--child-scenario=sleep", "--child-millis=20", "--wait=true");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().matches("child:\\d+\\nchild-exit:0\\n"));
        assertEquals("", run.stderr());
    }

    @Test
    void spawnTreeScenarioExposesChildAndGrandchildPids() throws Exception {
        ProcessRun run = runProcess("", "spawn-tree", "--leaf-scenario=sleep", "--leaf-millis=20", "--wait=true");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().matches("child:\\d+\\ngrandchild:\\d+\\nchild-exit:0\\n"));
        assertEquals("", run.stderr());
    }

    @Test
    void repeatSpawnScenarioModelsRepeatedShortLivedChildProcesses() throws Exception {
        ProcessRun run =
                runProcess("", "repeat-spawn", "--count=3", "--child-scenario=exit", "--child-arg=--exit-code=0");

        assertEquals(0, run.exitCode());
        assertEquals("iteration:0:exit:0\niteration:1:exit:0\niteration:2:exit:0\n", run.stdout());
        assertEquals("", run.stderr());
    }

    @Test
    void terminalCheckCanModelTerminalRequiredFailuresUnderPipes() throws Exception {
        ProcessRun run = runProcess("", "terminal-check", "--failure-exit-code=41");

        assertEquals(41, run.exitCode());
        assertEquals("terminal:missing\n", run.stdout());
    }

    @Test
    void shutdownHookScenarioModelsSlowJvmShutdown() throws Exception {
        ProcessRun run = runProcess("", "shutdown-hook", "--run-millis=0", "--hook-delay-millis=1");

        assertEquals(0, run.exitCode());
        assertEquals("started\nshutdown-hook:start\nshutdown-hook:end\n", run.stdout());
    }

    private static ProcessRun runProcess(String stdin, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TestCli.class.getName());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        boolean finished = process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("test CLI process did not finish");
        }
        return new ProcessRun(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private record ProcessRun(int exitCode, String stdout, String stderr) {}
}
