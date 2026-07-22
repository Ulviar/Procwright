/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.consumer.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.testcli.TestCli;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ConsumerScenariosTest {

    @Test
    void runExampleExecutesFiniteCommand() {
        CommandResult result = ConsumerScenarios.run(testCliCommand());

        assertEquals(0, result.exitCode().orElseThrow());
        assertEquals("echo:hello", result.stdout());
    }

    @Test
    void lineSessionExampleExecutesRequestResponseWorker() {
        LineResponse response = ConsumerScenarios.lineSession(testCliCommand());

        assertEquals("response:status", response.text());
    }

    @Test
    void protocolSessionExampleExecutesFramedWorker() {
        assertEquals("one\ntwo", ConsumerScenarios.protocolSession(testCliCommand()));
    }

    @Test
    void pooledLineSessionExampleExecutesThroughWorkerPool() {
        LineResponse response = ConsumerScenarios.pooledLineSession(testCliCommand());

        assertEquals("response:status", response.text());
        assertTrue(response.elapsed().toNanos() > 0);
    }

    @Test
    void pooledProtocolSessionExampleExecutesTypedPool() {
        assertEquals("pooled\nbody", ConsumerScenarios.pooledProtocolSession(testCliCommand()));
    }

    private static CommandSpec testCliCommand() {
        return CommandSpec.of(javaExecutable())
                .withArgs("-cp", System.getProperty("java.class.path"), TestCli.class.getName());
    }

    private static String javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }
}
