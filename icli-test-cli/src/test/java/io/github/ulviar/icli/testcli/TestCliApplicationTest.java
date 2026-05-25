package io.github.ulviar.icli.testcli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TestCliApplicationTest {

    @Test
    void catalogListsProblemFamiliesAndScenarioNames() throws Exception {
        Run run = run("", "catalog");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdoutText().contains("stream"));
        assertTrue(run.stdoutText().contains("stdin-echo"));
        assertTrue(run.stdoutText().contains("content-length"));
        assertTrue(run.stdoutText().contains("shutdown-hook"));
        assertTrue(run.stdoutText().contains("spawn-tree"));
        assertTrue(run.stdoutText().contains("long-run"));
        assertTrue(run.stdoutText().contains("platform-newlines"));
        assertTrue(run.stdoutText().contains("mixed-load"));
        assertEquals("", run.stderrText());
    }

    @Test
    void exitScenarioModelsStdoutStderrAndNonZeroExit() throws Exception {
        Run run = run("", "exit", "--stdout=out", "--stderr=err", "--exit-code=17");

        assertEquals(17, run.exitCode());
        assertEquals("out", run.stdoutText());
        assertEquals("err", run.stderrText());
    }

    @Test
    void streamScenarioModelsInterleavedOutputWithFlushBoundaries() throws Exception {
        Run run = run(
                "", "stream", "--count=3", "--stdout-template=out-%d", "--stderr-template=err-%d", "--delay-millis=0");

        assertEquals(0, run.exitCode());
        assertEquals("out-0\nout-1\nout-2\n", run.stdoutText());
        assertEquals("err-0\nerr-1\nerr-2\n", run.stderrText());
    }

    @Test
    void burstScenarioModelsIndependentLargeStreams() throws Exception {
        Run run = run("", "burst", "--stdout-bytes=7", "--stderr-bytes=5", "--stdout-byte=A", "--stderr-byte=e");

        assertEquals(0, run.exitCode());
        assertEquals("AAAAAAA", run.stdoutText());
        assertEquals("eeeee", run.stderrText());
    }

    @Test
    void binaryScenarioWritesRawBytesWithoutTextNormalization() throws Exception {
        Run run = run("", "binary", "--pattern=hex", "--hex=00ff410a", "--repeat=2");

        assertEquals(0, run.exitCode());
        assertArrayEquals(new byte[] {0, (byte) 255, 65, 10, 0, (byte) 255, 65, 10}, run.stdout());
    }

    @Test
    void partialAndAnsiScenariosModelUnterminatedPromptLikeOutput() throws Exception {
        Run partial = run("", "partial", "--stdout=half-out", "--stderr=half-err", "--hold-millis=0");
        Run ansi = run("", "ansi-prompt", "--prompt=READY", "--hold-millis=0");

        assertEquals("half-out", partial.stdoutText());
        assertEquals("half-err", partial.stderrText());
        assertEquals("\u001B[31mREADY\u001B[0m> ", ansi.stdoutText());
    }

    @Test
    void longRunScenarioModelsBoundedHeartbeatOutput() throws Exception {
        Run run = run("", "long-run", "--ticks=3", "--interval-millis=0", "--stderr-every=2", "--payload=:payload");

        assertEquals(0, run.exitCode());
        assertEquals("tick:0:payload\ntick:1:payload\ntick:2:payload\n", run.stdoutText());
        assertEquals("err-tick:0\nerr-tick:2\n", run.stderrText());
    }

    @Test
    void stdinEchoCanReturnHexForBinaryInput() throws Exception {
        Run run = run("A\u0000\u00ff", "stdin-echo", "--mode=hex");

        assertEquals(0, run.exitCode());
        assertEquals("4100c3bf\n", run.stdoutText());
    }

    @Test
    void lineReplSupportsPromptDelayStderrMultiLineAndExitCommands() throws Exception {
        Run run = run(
                "first\n:stderr diagnostic\n:multi 2\n:exit 9\n",
                "line-repl",
                "--prompt=ready> ",
                "--response-prefix=response:");

        assertEquals(9, run.exitCode());
        assertEquals("ready> response:first\nready> ready> multi:0\nmulti:1\nready> bye\n", run.stdoutText());
        assertEquals("diagnostic\n", run.stderrText());
    }

    @Test
    void jsonLinesCanReturnValidAndMalformedResponses() throws Exception {
        Run ok = run("{\"request\":1}\n", "jsonl", "--malformed-every=0");
        Run malformed = run("{}\n{}\n", "jsonl", "--malformed-every=2");

        assertEquals("{\"ok\":true,\"line\":1,\"bytes\":13}\n", ok.stdoutText());
        assertEquals("{\"ok\":true,\"line\":1,\"bytes\":2}\n{malformed-json\n", malformed.stdoutText());
    }

    @Test
    void contentLengthScenarioEchoesFrameMetadata() throws Exception {
        Run run = run("Content-Length: 5\r\n\r\nhello", "content-length");

        assertEquals(0, run.exitCode());
        assertEquals("Content-Length: 21\r\n\r\n{\"ok\":true,\"bytes\":5}", run.stdoutText());
    }

    @Test
    void contentLengthScenarioRejectsIncompleteFrames() {
        assertThrows(Exception.class, () -> run("Content-Length: 10\r\n\r\nshort", "content-length"));
    }

    @Test
    void argvEnvCwdScenarioModelsLaunchContext() throws Exception {
        Path cwd = Path.of("build", "test-cwd").toAbsolutePath().normalize();
        Run run = run(
                "",
                Map.of("ICLI_TEST_VALUE", "visible"),
                cwd,
                "argv-env-cwd",
                "--env=ICLI_TEST_VALUE",
                "--",
                "one",
                "two words");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdoutText().contains("cwd:" + cwd));
        assertTrue(run.stdoutText().contains("env:ICLI_TEST_VALUE=visible"));
        assertTrue(run.stdoutText().contains("argv:one|two words"));
    }

    @Test
    void platformNewlinesCanEmitExplicitCrLfFrames() throws Exception {
        Run run = run("", "platform-newlines", "--style=crlf", "--stdout-lines=2", "--stderr-lines=1");

        assertEquals(0, run.exitCode());
        assertEquals("out:0\r\nout:1\r\n", run.stdoutText());
        assertEquals("err:0\r\n", run.stderrText());
    }

    @Test
    void platformProbeReportsRuntimeSeparators() throws Exception {
        Run run = run("", "platform-probe");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdoutText().contains("os.name:"));
        assertTrue(run.stdoutText().contains("file.separator:"));
        assertTrue(run.stdoutText().contains("path.separator:"));
        assertTrue(run.stdoutText().contains("line.separator.hex:"));
    }

    @Test
    void mixedLoadCombinesBoundedMemoryCpuAndOutput() throws Exception {
        Run run = run(
                "",
                "mixed-load",
                "--ticks=2",
                "--cpu-millis=1",
                "--interval-millis=0",
                "--memory-bytes=1k",
                "--stderr-every=1");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdoutText().contains("load:0:ops:"));
        assertTrue(run.stdoutText().contains(":memory:1024"));
        assertEquals("load-err:0\nload-err:1\n", run.stderrText());
    }

    @Test
    void flakyScenarioIsDeterministicFromSeedAndConfiguredRates() throws Exception {
        Run run =
                run("", "flaky", "--seed=100", "--fail-percent=100", "--max-delay-millis=0", "--failure-exit-code=77");

        assertEquals(77, run.exitCode());
        assertTrue(run.stderrText().startsWith("flaky:failed:"));
    }

    private static Run run(String stdin, String... args) throws Exception {
        return run(stdin, Map.of(), Path.of("").toAbsolutePath().normalize(), args);
    }

    private static Run run(String stdin, Map<String, String> env, Path cwd, String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = TestCliApplication.run(
                args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), stdout, stderr, env, cwd);
        return new Run(exitCode, stdout.toByteArray(), stderr.toByteArray());
    }

    private record Run(int exitCode, byte[] stdout, byte[] stderr) {
        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String stderrText() {
            return new String(stderr, StandardCharsets.UTF_8);
        }
    }
}
