/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.internal.ProcessKernel;

final class OneShotIntegrationFixtures {

    private OneShotIntegrationFixtures() {}

    static CommandService fixtureService() {
        return fixtureService(ProcessKernel.standard());
    }

    static CommandService fixtureService(ProcessKernel processKernel) {
        return new CommandService(TestCliSupport.command(), processKernel);
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    static void assertStdoutEquals(String expected, CommandResult result) {
        assertEquals(expected, normalizeLineEndings(result.stdout()));
    }

    static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    static java.util.List<Byte> boxed(byte[] bytes) {
        java.util.ArrayList<Byte> result = new java.util.ArrayList<>();
        for (byte value : bytes) {
            result.add(value);
        }
        return result;
    }
}
