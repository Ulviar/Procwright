/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.testcli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class LifecycleScenarios {

    private static final Set<String> PROCESS_SPAWNING_SCENARIOS = Set.of("spawn-child", "spawn-tree", "repeat-spawn");

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
        // Optional side channel: a parent that terminates this process usually loses its stdout
        // pipe before the hook output arrives, so the hook can also record progress in a file.
        String hookFile = options.string("hook-file", "");
        String readyFile = options.string("ready-file", "");
        String hookChildPidFile = options.string("hook-child-pid-file", "");
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> runShutdownHook(context, stdout, hookDelayMillis, hookFile, hookChildPidFile),
                        "procwright-test-cli-shutdown-hook"));

        context.stdoutLine(options.string("started-text", "started"));
        appendHookMarker(readyFile, "ready\n");
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
        if (options.bool("close-stdin", false)) {
            context.stdin().close();
        }
        String childScenario = options.string("child-scenario", "sleep");
        List<String> command = testCliCommand(childScenario);
        command.add("--await-start-signal=true");
        if ("sleep".equals(childScenario)) {
            command.add("--millis=" + options.longValue("child-millis", 1000));
        } else if ("never-exit".equals(childScenario)) {
            command.add("--started-text=child-started");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(
                options.bool("inherit-output", false)
                        ? ProcessBuilder.Redirect.INHERIT
                        : ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        OwnedProcess ownedChild = observeStarted(child);
        boolean handedOff = false;
        Throwable failure = null;
        try {
            String pidFile = options.string("pid-file", "");
            if (!pidFile.isEmpty()) {
                Files.writeString(Path.of(pidFile), Long.toString(ownedChild.pid()));
            }
            String identityFile = options.string("identity-file", "");
            if (!identityFile.isEmpty()) {
                publishProcessIdentity(Path.of(identityFile), ownedChild);
            }
            String identitySuffix =
                    options.bool("include-start-identity", false) ? ":" + ownedChild.startInstant() : "";
            context.stdoutLine("child:" + ownedChild.pid() + identitySuffix);
            if (options.bool("await-child-release-signal", false)
                    && context.stdin().read() < 0) {
                throw new IOException("parent closed the child-release gate before acknowledging the process");
            }
            releaseStartSignal(child);
            handedOff = true;
            context.sleepMillis(options.longValue("linger-millis", 0));
            if (options.bool("wait", false)) {
                context.stdoutLine("child-exit:" + child.waitFor());
            }
            return options.integer("exit-code", 0);
        } catch (Exception | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (!handedOff) {
                cleanFailedHandoff(List.of(ownedChild), failure);
            }
        }
    }

    static int spawnTree(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        String leafScenario = options.string("leaf-scenario", "sleep");
        if (PROCESS_SPAWNING_SCENARIOS.contains(leafScenario)) {
            throw new IllegalArgumentException("leaf-scenario must not launch another process: " + leafScenario);
        }
        List<String> command = testCliCommand("spawn-child");
        command.add("--await-start-signal=true");
        command.add("--child-scenario=" + leafScenario);
        command.add("--wait=true");
        command.add("--include-start-identity=true");
        command.add("--await-child-release-signal=true");
        if ("sleep".equals(leafScenario)) {
            command.add("--child-millis=" + options.longValue("leaf-millis", 1000));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        OwnedProcess ownedChild = observeStarted(child);
        ArrayList<OwnedProcess> ownedProcesses = new ArrayList<>();
        ownedProcesses.add(ownedChild);
        boolean handedOff = false;
        Throwable failure = null;
        try {
            releaseStartSignal(child);

            BufferedReader childStdout =
                    new BufferedReader(new InputStreamReader(child.getInputStream(), context.charset()));
            OwnedProcess grandchild = readSpawnedIdentity(childStdout);
            ownedProcesses.add(grandchild);

            String grandchildPidFile = options.string("grandchild-pid-file", "");
            if (!grandchildPidFile.isEmpty()) {
                Files.writeString(Path.of(grandchildPidFile), Long.toString(grandchild.pid()));
            }
            String grandchildIdentityFile = options.string("grandchild-identity-file", "");
            if (!grandchildIdentityFile.isEmpty()) {
                publishProcessIdentity(Path.of(grandchildIdentityFile), grandchild);
            }
            awaitHandoffGate(options);
            String childPidFile = options.string("child-pid-file", "");
            if (!childPidFile.isEmpty()) {
                Files.writeString(Path.of(childPidFile), Long.toString(ownedChild.pid()));
            }
            String childIdentityFile = options.string("child-identity-file", "");
            if (!childIdentityFile.isEmpty()) {
                publishProcessIdentity(Path.of(childIdentityFile), ownedChild);
            }
            releaseStartSignal(child);
            context.stdoutLine("child:" + ownedChild.pid());
            context.stdoutLine("grandchild:" + grandchild.pid());
            handedOff = true;

            if (options.bool("wait", false)) {
                boolean exited = child.waitFor(options.longValue("wait-millis", 5000), TimeUnit.MILLISECONDS);
                if (exited) {
                    context.stdoutLine("child-exit:" + child.exitValue());
                } else {
                    stopProcessTree(ownedProcesses);
                    context.stdoutLine("child-timeout");
                }
            }
            return options.integer("exit-code", 0);
        } catch (Exception | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (!handedOff) {
                cleanFailedHandoff(ownedProcesses, failure);
            }
        }
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

    private static void runShutdownHook(
            ScenarioContext context,
            OutputStream stdout,
            long hookDelayMillis,
            String hookFile,
            String hookChildPidFile) {
        try {
            stdout.write("shutdown-hook:start\n".getBytes(context.charset()));
            stdout.flush();
            appendHookMarker(hookFile, "shutdown-hook:start\n");
            if (!hookChildPidFile.isEmpty()) {
                Process child = new ProcessBuilder(testCliCommand("never-exit"))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                Files.writeString(Path.of(hookChildPidFile), Long.toString(child.pid()));
            }
            Thread.sleep(hookDelayMillis);
            stdout.write("shutdown-hook:end\n".getBytes(context.charset()));
            stdout.flush();
            appendHookMarker(hookFile, "shutdown-hook:end\n");
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void appendHookMarker(String hookFile, String marker) throws IOException {
        if (!hookFile.isEmpty()) {
            Files.writeString(
                    Path.of(hookFile),
                    marker,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
    }

    private static void publishProcessIdentity(Path target, OwnedProcess process) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path temporary =
                Files.createTempFile(absoluteTarget.getParent(), "." + absoluteTarget.getFileName() + '-', ".tmp");
        Throwable publicationFailure = null;
        try {
            Files.writeString(temporary, process.pid() + "\n" + process.startInstant() + "\n");
            Files.move(temporary, absoluteTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) {
            publicationFailure = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (publicationFailure != null) {
                    publicationFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void releaseStartSignal(Process process) throws IOException {
        OutputStream startupGate = process.getOutputStream();
        startupGate.write(1);
        startupGate.flush();
    }

    private static OwnedProcess readSpawnedIdentity(BufferedReader childStdout) throws IOException {
        String line = childStdout.readLine();
        if (line == null || !line.startsWith("child:")) {
            throw new IOException("spawned child did not publish its child identity");
        }
        String[] fields = line.split(":", 3);
        if (fields.length != 3) {
            throw new IOException("spawned child published a malformed child identity");
        }
        try {
            long pid = Long.parseLong(fields[1]);
            Instant startInstant = Instant.parse(fields[2]);
            ProcessHandle process = ProcessHandle.of(pid)
                    .orElseThrow(() -> new IOException("spawned child process is not observable: " + pid));
            OwnedProcess observed = observe(process);
            if (!observed.startInstant().equals(startInstant)) {
                throw new IOException("spawned child process identity no longer matches PID: " + pid);
            }
            return observed;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("spawned child published a malformed child identity", malformed);
        }
    }

    private static void awaitHandoffGate(CliOptions options) throws Exception {
        String gateFile = options.string("handoff-gate-file", "");
        if (gateFile.isEmpty()) {
            return;
        }
        Path gate = Path.of(gateFile);
        long timeoutMillis = options.longValue("handoff-gate-timeout-millis", 10_000);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!Files.isRegularFile(gate)) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new IOException("handoff gate was not released: " + gate);
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private static OwnedProcess observe(ProcessHandle process) throws IOException {
        Instant startInstant = process.info()
                .startInstant()
                .orElseThrow(() -> new IOException("process start identity is unavailable: " + process.pid()));
        return new OwnedProcess(process, startInstant);
    }

    private static OwnedProcess observeStarted(Process process) throws Exception {
        try {
            return observe(process.toHandle());
        } catch (Exception | Error observationFailure) {
            boolean interrupted = Thread.interrupted();
            try {
                try {
                    process.destroyForcibly();
                    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (process.isAlive()) {
                        long remainingNanos = deadlineNanos - System.nanoTime();
                        if (remainingNanos <= 0) {
                            observationFailure.addSuppressed(
                                    new IOException("unidentified child process did not stop: " + process.pid()));
                            break;
                        }
                        try {
                            process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
                        } catch (InterruptedException exception) {
                            interrupted = true;
                        }
                    }
                } catch (Exception | Error cleanupFailure) {
                    observationFailure.addSuppressed(cleanupFailure);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw observationFailure;
        }
    }

    private static void cleanFailedHandoff(List<OwnedProcess> ownedProcesses, Throwable primaryFailure)
            throws Exception {
        try {
            stopProcessTree(ownedProcesses);
        } catch (Exception | Error cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private static void stopProcessTree(List<OwnedProcess> initialProcesses) throws Exception {
        LinkedHashMap<ProcessIdentity, OwnedProcess> owned = new LinkedHashMap<>();
        initialProcesses.forEach(process -> owned.put(process.identity(), process));
        ArrayList<ProcessHandle> unidentified = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = false;
        try {
            while (System.nanoTime() < deadlineNanos) {
                for (OwnedProcess process : List.copyOf(owned.values())) {
                    if (process.isAlive()) {
                        for (ProcessHandle descendant :
                                process.handle().descendants().toList()) {
                            try {
                                OwnedProcess observed = observe(descendant);
                                owned.putIfAbsent(observed.identity(), observed);
                            } catch (IOException | RuntimeException observationFailure) {
                                if (descendant.isAlive()) {
                                    unidentified.add(descendant);
                                    descendant.destroyForcibly();
                                }
                            }
                        }
                    }
                }

                List<OwnedProcess> snapshot = List.copyOf(owned.values());
                for (int index = snapshot.size() - 1; index >= 0; index--) {
                    OwnedProcess process = snapshot.get(index);
                    if (process.isAlive()) {
                        process.handle().destroyForcibly();
                    }
                }
                for (ProcessHandle process : unidentified) {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
                if (owned.values().stream().noneMatch(OwnedProcess::isAlive)
                        && unidentified.stream().noneMatch(ProcessHandle::isAlive)) {
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(5);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            String alive = owned.values().stream()
                    .filter(OwnedProcess::isAlive)
                    .map(process -> Long.toString(process.pid()))
                    .reduce((left, right) -> left + "," + right)
                    .orElse("unknown");
            String unidentifiedAlive = unidentified.stream()
                    .filter(ProcessHandle::isAlive)
                    .map(process -> Long.toString(process.pid()))
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            if (!unidentifiedAlive.isEmpty()) {
                alive = "unknown".equals(alive) ? unidentifiedAlive : alive + "," + unidentifiedAlive;
            }
            throw new IOException("process tree did not stop during failed handoff cleanup: " + alive);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record ProcessIdentity(long pid, Instant startInstant) {}

    private record OwnedProcess(ProcessHandle handle, Instant startInstant) {

        private ProcessIdentity identity() {
            return new ProcessIdentity(pid(), startInstant);
        }

        private long pid() {
            return handle.pid();
        }

        private boolean isAlive() {
            if (!handle.isAlive()) {
                return false;
            }
            var observedStart = handle.info().startInstant();
            if (observedStart.isPresent() && observedStart.orElseThrow().equals(startInstant)) {
                return true;
            }
            if (!handle.isAlive()) {
                return false;
            }
            if (observedStart.isEmpty()) {
                return true;
            }
            throw new IllegalStateException("process identity changed before cleanup: " + pid());
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
