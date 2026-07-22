/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.testcli.TestCli;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class TestCliSupport {

    private TestCliSupport() {}

    public static CommandSpec command() {
        return CommandSpec.of(javaExecutable())
                .withArgs("-cp", System.getProperty("java.class.path"), TestCli.class.getName());
    }

    static long waitForPid(Path pidFile) throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        String lastContent = "";
        while (System.nanoTime() < deadlineNanos) {
            if (Files.isRegularFile(pidFile)) {
                lastContent = Files.readString(pidFile).trim();
                if (!lastContent.isEmpty()) {
                    try {
                        long pid = Long.parseLong(lastContent);
                        if (pid > 0) {
                            return pid;
                        }
                    } catch (NumberFormatException ignored) {
                        // The producer may still be replacing a partially written marker.
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("PID file did not contain a positive process id: " + pidFile + " (last content: '"
                + lastContent + "')");
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
