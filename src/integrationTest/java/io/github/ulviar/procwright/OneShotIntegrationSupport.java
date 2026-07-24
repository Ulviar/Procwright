/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.internal.ProcessKernel;
import java.time.Duration;

abstract class OneShotIntegrationSupport {

    static CommandService fixtureService() {
        return fixtureService(ProcessKernel.standard());
    }

    static CommandService fixtureService(ProcessKernel processKernel) {
        return new CommandService(TestCliSupport.command(), processKernel);
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    static RunScenario.Draft putWindowsSystemRootIfNeeded(RunScenario.Draft draft) {
        if (!isWindows()) {
            return draft;
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            return draft.withEnvironment("SystemRoot", systemRoot);
        }
        return draft;
    }

    static void assertStdoutEquals(String expected, CommandResult result) {
        assertEquals(expected, normalizeLineEndings(result.stdout()));
    }

    static void assertStderrEquals(String expected, CommandResult result) {
        assertEquals(expected, normalizeLineEndings(result.stderr()));
    }

    static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    static boolean isAliveEventually(long pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted", exception);
                }
                continue;
            }
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static java.util.List<Byte> boxed(byte[] bytes) {
        java.util.ArrayList<Byte> result = new java.util.ArrayList<>();
        for (byte value : bytes) {
            result.add(value);
        }
        return result;
    }
}
