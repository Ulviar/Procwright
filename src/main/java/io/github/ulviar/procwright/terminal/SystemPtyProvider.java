/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.internal.CommandValidation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

final class SystemPtyProvider implements PtyProvider {

    private static final Map<String, String> WRAPPER_LOCALE = Map.of("LC_ALL", "C", "LANG", "C", "TERM", "dumb");

    private final SystemPtySupport support;
    private final BootstrapPreparer bootstrapPreparer;
    private final ProcessStarter processStarter;
    private final Duration launchTimeout;

    SystemPtyProvider(SystemPtySupport support) {
        this(support, PtyBootstrap::prepare, ProcessBuilder::start, PtyBootstrap.MAX_BOOTSTRAP_TIMEOUT);
    }

    SystemPtyProvider(SystemPtySupport support, BootstrapPreparer bootstrapPreparer) {
        this(support, bootstrapPreparer, ProcessBuilder::start, PtyBootstrap.MAX_BOOTSTRAP_TIMEOUT);
    }

    SystemPtyProvider(
            SystemPtySupport support,
            BootstrapPreparer bootstrapPreparer,
            ProcessStarter processStarter,
            Duration launchTimeout) {
        this.support = Objects.requireNonNull(support, "support");
        this.bootstrapPreparer = Objects.requireNonNull(bootstrapPreparer, "bootstrapPreparer");
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.launchTimeout = Objects.requireNonNull(launchTimeout, "launchTimeout");
        if (launchTimeout.isZero()
                || launchTimeout.isNegative()
                || launchTimeout.compareTo(PtyLaunchAdmission.MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("launchTimeout must be between 1 ns and 10 seconds");
        }
    }

    static SystemPtyProvider instance() {
        return InstanceHolder.INSTANCE;
    }

    @Override
    public boolean available() {
        return support.available();
    }

    @Override
    public String description() {
        return support.description();
    }

    @Override
    public Process start(PtyRequest request) {
        Objects.requireNonNull(request, "request");
        if (!support.available()) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.LAUNCH_FAILED,
                    "PTY provider is unavailable: " + support.description());
        }

        try {
            return PtyLaunchAdmission.launch(launchTimeout, context -> {
                PtyLaunchPlan plan = planFor(request);
                PtyBootstrap.Prepared bootstrap = bootstrapPreparer.prepare(plan.payload());
                context.limitTo(bootstrap.timeout());
                ProcessBuilder builder = new ProcessBuilder(PtyBootstrap.commandFor(support, plan.terminalSize()));
                request.workingDirectory().ifPresent(path -> builder.directory(path.toFile()));
                builder.environment().clear();
                builder.environment().putAll(plan.wrapperEnvironment());
                Process process = processStarter.start(builder);
                context.registerProcess(process);
                return bootstrap.initialize(process, context);
            });
        } catch (IllegalArgumentException exception) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.LAUNCH_FAILED,
                    "Could not prepare a PTY target with " + argumentCount(request) + " argument(s)",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.LAUNCH_FAILED,
                    "PTY launch was interrupted for a target with " + argumentCount(request) + " argument(s)",
                    exception);
        } catch (IOException exception) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.LAUNCH_FAILED,
                    "Could not start a PTY target with " + argumentCount(request) + " argument(s)",
                    exception);
        }
    }

    PtyLaunchPlan planFor(PtyRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> childCommand = validateCommand(request.command());
        Map<String, String> childEnvironment = childEnvironmentFor(request);
        Map<String, String> wrapperEnvironment = wrapperEnvironmentFor(support);
        return new PtyLaunchPlan(
                wrapperEnvironment, new PtyPayload(childCommand, childEnvironment), request.terminalSize());
    }

    private static List<String> validateCommand(List<String> command) {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("PTY command must not be empty");
        }
        if (command.size() > PtyBootstrap.MAX_ENTRY_COUNT) {
            throw new IllegalArgumentException("PTY command contains too many entries");
        }
        ArrayList<String> validated = new ArrayList<>(command.size());
        String executable = CommandValidation.requireText(command.get(0), "PTY executable");
        if (executable.indexOf('=') >= 0) {
            throw new IllegalArgumentException("PTY executable is unsupported by the system provider");
        }
        validated.add(executable);
        for (int index = 1; index < command.size(); index++) {
            validated.add(CommandValidation.requireArgument(command.get(index)));
        }
        return List.copyOf(validated);
    }

    private static Map<String, String> childEnvironmentFor(PtyRequest request) {
        if (request.environment().size() > PtyBootstrap.MAX_ENTRY_COUNT) {
            throw new IllegalArgumentException("PTY environment contains too many entries");
        }
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        if (request.environmentPolicy() == EnvironmentPolicy.INHERIT) {
            new ProcessBuilder()
                    .environment()
                    .forEach((name, value) -> environment.put(
                            CommandValidation.requireEnvironmentName(name),
                            CommandValidation.requireEnvironmentValue(value)));
        }
        request.environment()
                .forEach((name, value) -> environment.put(
                        CommandValidation.requireEnvironmentName(name),
                        CommandValidation.requireEnvironmentValue(value)));
        environment.putIfAbsent("TERM", "xterm-256color");
        environment.put("COLUMNS", Integer.toString(request.terminalSize().columns()));
        environment.put("LINES", Integer.toString(request.terminalSize().rows()));
        if (environment.size() > PtyBootstrap.MAX_ENTRY_COUNT) {
            throw new IllegalArgumentException("PTY environment contains too many entries");
        }
        return Map.copyOf(environment);
    }

    static Map<String, String> wrapperEnvironmentFor(SystemPtySupport support) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>(WRAPPER_LOCALE);
        environment.put("SHELL", support.shellPath().toString());
        return Map.copyOf(environment);
    }

    private static int argumentCount(PtyRequest request) {
        return Math.max(0, request.command().size() - 1);
    }

    private static Path requireAbsolutePath(Path path, String name) {
        Objects.requireNonNull(path, name);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute");
        }
        return path;
    }

    record PtyLaunchPlan(Map<String, String> wrapperEnvironment, PtyPayload payload, TerminalSize terminalSize) {

        PtyLaunchPlan {
            wrapperEnvironment = Map.copyOf(wrapperEnvironment);
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(terminalSize, "terminalSize");
        }
    }

    record PtyPayload(List<String> command, Map<String, String> environment) {

        PtyPayload {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
        }
    }

    enum ScriptFlavor {
        BSD,
        UTIL_LINUX,
        UNAVAILABLE
    }

    @FunctionalInterface
    interface ScriptCapabilityProbe {

        boolean supports(ScriptFlavor flavor, SystemTools tools);
    }

    @FunctionalInterface
    interface BootstrapPreparer {

        PtyBootstrap.Prepared prepare(PtyPayload payload) throws IOException;
    }

    @FunctionalInterface
    interface ProcessStarter {

        Process start(ProcessBuilder builder) throws IOException;
    }

    record SystemTools(Path scriptPath, Path shellPath, Path sttyPath, Path envPath, Path ddPath) {

        SystemTools {
            scriptPath = requireAbsolutePath(scriptPath, "scriptPath");
            shellPath = requireAbsolutePath(shellPath, "shellPath");
            sttyPath = requireAbsolutePath(sttyPath, "sttyPath");
            envPath = requireAbsolutePath(envPath, "envPath");
            ddPath = requireAbsolutePath(ddPath, "ddPath");
        }
    }

    record SystemPtySupport(
            ScriptFlavor flavor,
            Path scriptPath,
            Path shellPath,
            Path sttyPath,
            Path envPath,
            Path ddPath,
            String description) {

        SystemPtySupport {
            Objects.requireNonNull(flavor, "flavor");
            if (flavor != ScriptFlavor.UNAVAILABLE) {
                scriptPath = requireAbsolutePath(scriptPath, "scriptPath");
                shellPath = requireAbsolutePath(shellPath, "shellPath");
                sttyPath = requireAbsolutePath(sttyPath, "sttyPath");
                envPath = requireAbsolutePath(envPath, "envPath");
                ddPath = requireAbsolutePath(ddPath, "ddPath");
            }
            CommandValidation.requireText(description, "description");
        }

        boolean available() {
            return flavor != ScriptFlavor.UNAVAILABLE;
        }

        static SystemPtySupport detect() {
            return detect(
                    System.getProperty("os.name", ""),
                    SystemPtySupport::isTrustedExecutable,
                    new SystemPtyCapabilityProbe());
        }

        static SystemPtySupport detect(
                String osName, Predicate<Path> executableProbe, ScriptCapabilityProbe capabilityProbe) {
            Objects.requireNonNull(osName, "osName");
            Objects.requireNonNull(executableProbe, "executableProbe");
            Objects.requireNonNull(capabilityProbe, "capabilityProbe");
            String normalizedOsName = osName.strip().toLowerCase(Locale.ROOT);
            if (normalizedOsName.startsWith("windows")) {
                return unavailable("Windows ConPTY support is not implemented in the core artifact yet");
            }

            List<Path> scripts =
                    trustedExecutables(List.of(Path.of("/usr/bin/script"), Path.of("/bin/script")), executableProbe);
            if (scripts.isEmpty()) {
                return unavailable("Unix PTY support requires script(1) in a trusted system path");
            }
            Path shell = Path.of("/bin/sh");
            if (!executableProbe.test(shell)) {
                return unavailable("Unix PTY support requires executable /bin/sh");
            }
            Path stty = findTrustedExecutable(List.of(Path.of("/usr/bin/stty"), Path.of("/bin/stty")), executableProbe);
            if (stty == null) {
                return unavailable("Unix PTY support requires stty(1) in a trusted system path");
            }
            Path env = findTrustedExecutable(List.of(Path.of("/usr/bin/env"), Path.of("/bin/env")), executableProbe);
            if (env == null) {
                return unavailable("Unix PTY support requires env(1) in a trusted system path");
            }
            Path dd = findTrustedExecutable(List.of(Path.of("/usr/bin/dd"), Path.of("/bin/dd")), executableProbe);
            if (dd == null) {
                return unavailable("Unix PTY support requires dd(1) in a trusted system path");
            }

            List<ScriptFlavor> candidates = normalizedOsName.contains("linux")
                    ? List.of(ScriptFlavor.UTIL_LINUX, ScriptFlavor.BSD)
                    : List.of(ScriptFlavor.BSD, ScriptFlavor.UTIL_LINUX);
            for (Path script : scripts) {
                SystemTools tools = new SystemTools(script, shell, stty, env, dd);
                for (ScriptFlavor candidate : candidates) {
                    if (capabilityProbe.supports(candidate, tools)) {
                        String label = candidate == ScriptFlavor.UTIL_LINUX ? "util-linux" : "BSD";
                        return new SystemPtySupport(
                                candidate, script, shell, stty, env, dd, label + " script(1) PTY provider");
                    }
                }
            }
            return unavailable("Unix script(1) failed the bounded PTY capability probe");
        }

        private static SystemPtySupport unavailable(String description) {
            return new SystemPtySupport(ScriptFlavor.UNAVAILABLE, null, null, null, null, null, description);
        }

        private static Path findTrustedExecutable(List<Path> candidates, Predicate<Path> executableProbe) {
            List<Path> matches = trustedExecutables(candidates, executableProbe);
            return matches.isEmpty() ? null : matches.get(0);
        }

        private static List<Path> trustedExecutables(List<Path> candidates, Predicate<Path> executableProbe) {
            ArrayList<Path> matches = new ArrayList<>();
            for (Path candidate : candidates) {
                if (executableProbe.test(candidate) && !matches.contains(candidate)) {
                    matches.add(candidate);
                }
            }
            return List.copyOf(matches);
        }

        private static boolean isTrustedExecutable(Path candidate) {
            try {
                return candidate.isAbsolute() && Files.isRegularFile(candidate) && Files.isExecutable(candidate);
            } catch (SecurityException ignored) {
                return false;
            }
        }
    }

    private static final class InstanceHolder {

        private static final SystemPtyProvider INSTANCE = new SystemPtyProvider(SystemPtySupport.detect());
    }
}
