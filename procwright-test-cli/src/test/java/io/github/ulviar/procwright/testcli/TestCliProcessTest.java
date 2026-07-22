/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.testcli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestCliProcessTest {

    @TempDir
    private Path temporaryDirectory;

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
    void spawnChildPidSideChannelRemainsAPlainProcessId() throws Exception {
        Path childPidFile = temporaryDirectory.resolve("child.pid");

        ProcessRun run = runProcess(
                "",
                "spawn-child",
                "--child-scenario=sleep",
                "--child-millis=20",
                "--wait=true",
                "--pid-file=" + childPidFile);

        assertEquals(0, run.exitCode());
        assertTrue(Files.readString(childPidFile).matches("\\d+"));
    }

    @Test
    void spawnTreeScenarioExposesChildAndGrandchildPids() throws Exception {
        ProcessRun run = runProcess("", "spawn-tree", "--leaf-scenario=sleep", "--leaf-millis=20", "--wait=true");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().matches("child:\\d+\\ngrandchild:\\d+\\nchild-exit:0\\n"));
        assertEquals("", run.stderr());
    }

    @Test
    void spawnTreeScenarioPublishesIdentitySideChannelsBeforeWaiting() throws Exception {
        Path childIdentityFile = temporaryDirectory.resolve("child.identity");
        Path grandchildIdentityFile = temporaryDirectory.resolve("grandchild.identity");
        try {
            ProcessRun run = runProcess(
                    "",
                    "spawn-tree",
                    "--leaf-scenario=sleep",
                    "--leaf-millis=20",
                    "--wait=true",
                    "--child-identity-file=" + childIdentityFile,
                    "--grandchild-identity-file=" + grandchildIdentityFile);

            List<String> childIdentity = Files.readAllLines(childIdentityFile);
            List<String> grandchildIdentity = Files.readAllLines(grandchildIdentityFile);
            assertEquals(0, run.exitCode());
            assertEquals("", run.stderr());
            assertEquals(2, childIdentity.size());
            assertEquals(2, grandchildIdentity.size());
            String childPid = childIdentity.get(0);
            String grandchildPid = grandchildIdentity.get(0);
            assertTrue(childPid.matches("\\d+"));
            assertTrue(grandchildPid.matches("\\d+"));
            Instant.parse(childIdentity.get(1));
            Instant.parse(grandchildIdentity.get(1));
            assertTrue(run.stdout().contains("child:" + childPid + "\n"));
            assertTrue(run.stdout().contains("grandchild:" + grandchildPid + "\n"));
        } finally {
            Files.deleteIfExists(childIdentityFile);
            Files.deleteIfExists(grandchildIdentityFile);
        }
    }

    @Test
    void spawnTreeScenarioStopsChildAndGrandchildWhenPidHandoffFails() throws Exception {
        Path grandchildIdentityFile = temporaryDirectory.resolve("failed-handoff-grandchild.identity");
        Path invalidChildIdentityFile = temporaryDirectory.resolve("missing").resolve("child.identity");
        Path handoffGateFile = temporaryDirectory.resolve("failed-handoff.gate");
        Process process = startProcess(
                "",
                "spawn-tree",
                "--leaf-scenario=never-exit",
                "--wait=true",
                "--handoff-gate-file=" + handoffGateFile,
                "--handoff-gate-timeout-millis=20000",
                "--child-identity-file=" + invalidChildIdentityFile,
                "--grandchild-identity-file=" + grandchildIdentityFile);
        ArrayList<ProcessHandle> observedProcesses = new ArrayList<>();
        Throwable primaryFailure = null;
        try {
            ProcessHandle grandchild = awaitPublishedProcess(grandchildIdentityFile, Duration.ofSeconds(10));
            observedProcesses.add(grandchild);
            ProcessHandle child = grandchild
                    .parent()
                    .orElseThrow(() -> new AssertionError("published grandchild has no observable parent"));
            observedProcesses.add(child);

            assertEquals(process.pid(), child.parent().orElseThrow().pid());
            Files.writeString(handoffGateFile, "release");
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "test CLI did not report the failed handoff");
            assertNotEquals(0, process.exitValue());
            assertEventuallyStopped(child, Duration.ofSeconds(5));
            assertEventuallyStopped(grandchild, Duration.ofSeconds(5));
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanProcessTreePreserving(process.toHandle(), observedProcesses, grandchildIdentityFile, primaryFailure);
        }
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

    @Test
    void shutdownHookScenarioCanRecordHookProgressInFile() throws Exception {
        Path hookFile = Files.createTempFile("procwright-test-cli-hook", ".txt");
        try {
            ProcessRun run = runProcess(
                    "", "shutdown-hook", "--run-millis=0", "--hook-delay-millis=1", "--hook-file=" + hookFile);

            assertEquals(0, run.exitCode());
            assertEquals("shutdown-hook:start\nshutdown-hook:end\n", Files.readString(hookFile));
        } finally {
            Files.deleteIfExists(hookFile);
        }
    }

    private static ProcessRun runProcess(String stdin, String... args) throws Exception {
        Process process = startProcess(stdin, args);

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

    private static Process startProcess(String stdin, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TestCli.class.getName());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        return process;
    }

    private static ProcessHandle awaitPublishedProcess(Path identityFile, Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (Files.isRegularFile(identityFile)) {
                List<String> identity = Files.readAllLines(identityFile);
                if (identity.size() == 2) {
                    long pid = Long.parseLong(identity.get(0));
                    Instant publishedStart = Instant.parse(identity.get(1));
                    ProcessHandle process = ProcessHandle.of(pid)
                            .orElseThrow(() -> new AssertionError("published process is not observable: " + pid));
                    assertEquals(publishedStart, process.info().startInstant().orElseThrow());
                    return process;
                }
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("process identity was not published: " + identityFile);
    }

    private static void assertEventuallyStopped(ProcessHandle process, Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (process.isAlive() && System.nanoTime() < deadlineNanos) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertFalse(process.isAlive(), "process remained alive after failed handoff cleanup: " + process.pid());
    }

    private static void cleanProcessTreePreserving(
            ProcessHandle root,
            ArrayList<ProcessHandle> observedProcesses,
            Path publishedIdentity,
            Throwable primaryFailure)
            throws Exception {
        boolean interrupted = Thread.interrupted() || primaryFailure instanceof InterruptedException;
        try {
            ArrayList<ProcessHandle> cleanupOrder = new ArrayList<>();
            cleanupOrder.add(root);
            cleanupOrder.addAll(observedProcesses);
            long identityDeadlineNanos =
                    System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (root.isAlive() && System.nanoTime() < identityDeadlineNanos) {
                Optional<ProcessHandle> published = tryReadPublishedProcess(publishedIdentity);
                if (published.isPresent()) {
                    ProcessHandle process = published.orElseThrow();
                    if (!cleanupOrder.contains(process)) {
                        cleanupOrder.add(process);
                    }
                    break;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }

            long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadlineNanos) {
                for (ProcessHandle process : List.copyOf(cleanupOrder)) {
                    if (process.isAlive()) {
                        for (ProcessHandle descendant : process.descendants().toList()) {
                            if (!cleanupOrder.contains(descendant)) {
                                cleanupOrder.add(descendant);
                            }
                        }
                    }
                }
                for (int index = cleanupOrder.size() - 1; index >= 0; index--) {
                    ProcessHandle process = cleanupOrder.get(index);
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
                if (cleanupOrder.stream().noneMatch(ProcessHandle::isAlive)) {
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            List<Long> alive = cleanupOrder.stream()
                    .filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::pid)
                    .distinct()
                    .toList();
            assertTrue(alive.isEmpty(), "processes remained alive after test cleanup: " + alive);
        } catch (Exception | Error cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Optional<ProcessHandle> tryReadPublishedProcess(Path identityFile) {
        try {
            if (!Files.isRegularFile(identityFile)) {
                return Optional.empty();
            }
            List<String> identity = Files.readAllLines(identityFile);
            if (identity.size() != 2) {
                return Optional.empty();
            }
            long pid = Long.parseLong(identity.get(0));
            Instant startInstant = Instant.parse(identity.get(1));
            return ProcessHandle.of(pid).filter(process -> process.info()
                    .startInstant()
                    .map(startInstant::equals)
                    .orElse(false));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private record ProcessRun(int exitCode, String stdout, String stderr) {}
}
