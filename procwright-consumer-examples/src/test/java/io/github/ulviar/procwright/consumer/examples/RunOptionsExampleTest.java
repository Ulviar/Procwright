/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.consumer.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.examples.RunOptionsExample;
import io.github.ulviar.procwright.testcli.TestCli;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunOptionsExampleTest {

    @TempDir
    Path directory;

    @Test
    void textInputReachesTheCommand() {
        CommandResult result =
                RunOptionsExample.textInput(testCommand().withArgs("stdin-echo", "--mode=text"), "Zażółć gęślą jaźń\n");

        assertTrue(result.succeeded());
        assertEquals("Zażółć gęślą jaźń\n", result.stdout());
    }

    @Test
    void fileInputAndSeparateOutputPreserveContentWithoutCapturingIt() throws Exception {
        Path input = directory.resolve("input.txt");
        Path stdout = directory.resolve("stdout.txt");
        Path stderr = directory.resolve("stderr.txt");
        Files.writeString(input, "first\nsecond\n");
        Files.writeString(stdout, "previous output must be overwritten");

        CommandResult result =
                RunOptionsExample.files(testCommand().withArgs("stdin-echo", "--mode=text"), input, stdout, stderr);

        assertTrue(result.succeeded());
        assertEquals(Files.readString(input), Files.readString(stdout));
        assertEquals("", Files.readString(stderr));
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void mergedFileIncludesBothStreams() throws Exception {
        Path log = directory.resolve("combined.log");
        CommandResult result = RunOptionsExample.mergedFile(
                testCommand().withArgs("exit", "--stdout=output", "--stderr=diagnostic"), log);

        assertTrue(result.succeeded());
        String output = Files.readString(log);
        assertTrue(output.contains("output"));
        assertTrue(output.contains("diagnostic"));
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    private static CommandSpec testCommand() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return CommandSpec.of(Path.of(System.getProperty("java.home"), "bin", executable)
                        .toString())
                .withArgs("-cp", System.getProperty("java.class.path"), TestCli.class.getName());
    }
}
