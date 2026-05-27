package io.github.ulviar.procwright.terminal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.internal.CommandEchoSupport;
import io.github.ulviar.procwright.internal.CommandValidation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class SystemPtyProvider implements PtyProvider {

    static final SystemPtyProvider INSTANCE = new SystemPtyProvider(SystemPtySupport.detect());

    private final SystemPtySupport support;

    private SystemPtyProvider(SystemPtySupport support) {
        this.support = Objects.requireNonNull(support, "support");
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
            throw new CommandExecutionException("PTY provider is unavailable: " + support.description());
        }

        ProcessBuilder builder = new ProcessBuilder(commandFor(request));
        request.workingDirectory().ifPresent(path -> builder.directory(path.toFile()));
        if (request.environmentPolicy() == EnvironmentPolicy.CLEAN) {
            builder.environment().clear();
        }
        builder.environment().putAll(environmentFor(request));

        try {
            return builder.start();
        } catch (IOException exception) {
            throw new CommandExecutionException(
                    "Could not start PTY command: " + CommandEchoSupport.redactedSummary(request.command()), exception);
        }
    }

    private List<String> commandFor(PtyRequest request) {
        return switch (support.flavor()) {
            case BSD -> bsdCommandFor(request);
            case UTIL_LINUX -> utilLinuxCommandFor(request);
            case UNAVAILABLE -> throw new CommandExecutionException("PTY provider is unavailable: " + description());
        };
    }

    private List<String> bsdCommandFor(PtyRequest request) {
        ArrayList<String> command = new ArrayList<>();
        command.add(support.scriptPath().toString());
        command.add("-q");
        command.add("/dev/null");
        command.add("sh");
        command.add("-c");
        command.add("stty rows \"$1\" cols \"$2\" 2>/dev/null || true; shift 2; exec \"$@\"");
        command.add("procwright-pty");
        command.add(Integer.toString(request.terminalSize().rows()));
        command.add(Integer.toString(request.terminalSize().columns()));
        command.addAll(request.command());
        return command;
    }

    private List<String> utilLinuxCommandFor(PtyRequest request) {
        ArrayList<String> command = new ArrayList<>();
        command.add(support.scriptPath().toString());
        command.add("-q");
        command.add("-c");
        command.add("stty rows "
                + request.terminalSize().rows()
                + " cols "
                + request.terminalSize().columns()
                + " 2>/dev/null || true; exec "
                + shellCommand(request.command()));
        command.add("/dev/null");
        return command;
    }

    private Map<String, String> environmentFor(PtyRequest request) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.putAll(request.environment());
        environment.putIfAbsent("TERM", "xterm-256color");
        environment.put("COLUMNS", Integer.toString(request.terminalSize().columns()));
        environment.put("LINES", Integer.toString(request.terminalSize().rows()));
        return environment;
    }

    private static String shellCommand(List<String> command) {
        ArrayList<String> quoted = new ArrayList<>(command.size());
        for (String part : command) {
            quoted.add(shellQuote(part));
        }
        return String.join(" ", quoted);
    }

    private static String shellQuote(String value) {
        if (value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private enum ScriptFlavor {
        BSD,
        UTIL_LINUX,
        UNAVAILABLE
    }

    private record SystemPtySupport(ScriptFlavor flavor, Path scriptPath, String description) {

        private SystemPtySupport {
            Objects.requireNonNull(flavor, "flavor");
            if (flavor != ScriptFlavor.UNAVAILABLE) {
                Objects.requireNonNull(scriptPath, "scriptPath");
            }
            CommandValidation.requireText(description, "description");
        }

        boolean available() {
            return flavor != ScriptFlavor.UNAVAILABLE;
        }

        static SystemPtySupport detect() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (osName.contains("win")) {
                return unavailable("Windows ConPTY support is not implemented in the core artifact yet");
            }

            Path script = findScript();
            if (script == null) {
                return unavailable("Unix PTY support requires script(1) in a trusted system path");
            }

            if (osName.contains("linux")) {
                return new SystemPtySupport(ScriptFlavor.UTIL_LINUX, script, "util-linux script(1) PTY provider");
            }
            return new SystemPtySupport(ScriptFlavor.BSD, script, "BSD script(1) PTY provider");
        }

        private static SystemPtySupport unavailable(String description) {
            return new SystemPtySupport(ScriptFlavor.UNAVAILABLE, null, description);
        }

        private static Path findScript() {
            for (Path candidate : List.of(Path.of("/usr/bin/script"), Path.of("/bin/script"))) {
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
