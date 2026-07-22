/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class SystemShell {

    private static final Platform SYSTEM_PLATFORM = new SystemPlatform();

    private SystemShell() {}

    public static List<String> command(String commandLine) {
        return command(commandLine, SYSTEM_PLATFORM);
    }

    static List<String> command(String commandLine, Platform platform) {
        if (platform.isWindows()) {
            Path interpreter = trustedWindowsCommandInterpreter(platform);
            return List.of(interpreter.toString(), "/d", "/s", "/c", commandLine);
        }
        return List.of("/bin/sh", "-c", commandLine);
    }

    private static Path trustedWindowsCommandInterpreter(Platform platform) {
        try {
            Path configuredRoot = platform.windowsSystemRoot().orElseThrow(SystemShell::unavailableWindowsShell);
            if (!configuredRoot.isAbsolute()) {
                throw unavailableWindowsShell();
            }

            Path canonicalRoot = platform.toRealPath(configuredRoot);
            Path expectedSystemDirectory = canonicalRoot.resolve("System32").normalize();
            Path systemDirectory = platform.toRealPath(expectedSystemDirectory);
            Path interpreter = platform.toRealPath(systemDirectory.resolve("cmd.exe"));
            if (!canonicalRoot.isAbsolute()
                    || !systemDirectory.isAbsolute()
                    || !interpreter.isAbsolute()
                    || !platform.sameWindowsPath(systemDirectory, expectedSystemDirectory)
                    || !platform.sameWindowsPath(systemDirectory, interpreter.getParent())
                    || !interpreter.getFileName().toString().equalsIgnoreCase("cmd.exe")
                    || !platform.isRegularFile(interpreter)
                    || !platform.isReadable(interpreter)
                    || !platform.isExecutable(interpreter)) {
                throw unavailableWindowsShell();
            }
            return interpreter;
        } catch (IOException | SecurityException failure) {
            throw unavailableWindowsShell();
        }
    }

    private static CommandExecutionException unavailableWindowsShell() {
        return new CommandExecutionException(
                CommandExecutionException.Reason.LAUNCH_FAILED, "Trusted Windows command interpreter is unavailable");
    }

    interface Platform {
        boolean isWindows();

        Optional<Path> windowsSystemRoot();

        Path toRealPath(Path path) throws IOException;

        boolean isRegularFile(Path path);

        boolean isReadable(Path path);

        boolean isExecutable(Path path);

        boolean sameWindowsPath(Path first, Path second);
    }

    private static final class SystemPlatform implements Platform {

        @Override
        public boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }

        @Override
        public Optional<Path> windowsSystemRoot() {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Path.of(systemRoot));
            } catch (InvalidPathException failure) {
                return Optional.empty();
            }
        }

        @Override
        public Path toRealPath(Path path) throws IOException {
            return path.toRealPath();
        }

        @Override
        public boolean isRegularFile(Path path) {
            return Files.isRegularFile(path);
        }

        @Override
        public boolean isReadable(Path path) {
            return Files.isReadable(path);
        }

        @Override
        public boolean isExecutable(Path path) {
            return Files.isExecutable(path);
        }

        @Override
        public boolean sameWindowsPath(Path first, Path second) {
            String normalizedFirst = first.toAbsolutePath().normalize().toString();
            String normalizedSecond = second.toAbsolutePath().normalize().toString();
            return normalizedFirst.equalsIgnoreCase(normalizedSecond);
        }
    }
}
