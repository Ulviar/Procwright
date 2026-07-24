/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

abstract class OneShotExecutionIntegrationSupport extends OneShotIntegrationSupport {

    static String shellEchoEnvironmentCommand(String variableName) {
        if (isWindows()) {
            return "echo shell:%" + variableName + "%";
        }
        return "printf 'shell:%s\\n' \"$" + variableName + "\"";
    }

    static void assertWindowsShellIgnoresPoisonedJvmWorkingDirectory(Path workingDirectory, boolean cleanEnvironment)
            throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Files.copy(javaExecutable, workingDirectory.resolve("cmd.exe"));

        ProcessBuilder builder = new ProcessBuilder(
                        javaExecutable.toString(),
                        "-cp",
                        absoluteClasspath(),
                        WindowsShellPoisonProbe.class.getName(),
                        Boolean.toString(cleanEnvironment))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        String originalPath = builder.environment().getOrDefault("PATH", "");
        builder.environment().put("PATH", workingDirectory + File.pathSeparator + originalPath);

        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(finished, "nested Windows shell probe did not finish");
        assertEquals(0, process.exitValue(), () -> "nested Windows shell probe failed:\n" + output);
    }

    static String absoluteClasspath() {
        return Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(System.getProperty("java.class.path"))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }
}
