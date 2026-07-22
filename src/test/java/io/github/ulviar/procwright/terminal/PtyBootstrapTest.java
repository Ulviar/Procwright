/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PtyBootstrapTest {

    private static final byte[] HANDSHAKE =
            ("\u001ePROCWRIGHT_PTY_READY\u001f\u001ePROCWRIGHT_PTY_STARTED\u001f").getBytes(StandardCharsets.US_ASCII);

    @Test
    void bootstrapProgramIsValidForTheTrustedPosixShell() throws Exception {
        Path shell = Path.of("/bin/sh");
        assumeTrue(Files.isExecutable(shell));
        Process syntaxCheck = new ProcessBuilder(shell.toString(), "-n", "-c", PtyBootstrap.SHELL_PROGRAM)
                .redirectErrorStream(true)
                .start();

        assertTrue(syntaxCheck.waitFor(1, TimeUnit.SECONDS));
        assertEquals(0, syntaxCheck.exitValue(), () -> {
            try {
                return new String(syntaxCheck.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                return exception.toString();
            }
        });
    }

    @Test
    void payloadAppliesPerFieldTotalAndEntryBoundsWithoutEchoingValues() throws Exception {
        String oversizedSecret = "secret-" + "x".repeat(PtyBootstrap.MAX_FIELD_BYTES);
        IOException fieldFailure = assertThrows(
                IOException.class,
                () -> PtyBootstrap.prepare(payload(List.of("/bin/true", oversizedSecret), Map.of())));

        ArrayList<String> totalOverflow = new ArrayList<>();
        totalOverflow.add("/bin/true");
        for (int index = 0; index < 5; index++) {
            totalOverflow.add("x".repeat(PtyBootstrap.MAX_FIELD_BYTES));
        }
        IOException totalFailure =
                assertThrows(IOException.class, () -> PtyBootstrap.prepare(payload(totalOverflow, Map.of())));

        List<String> tooManyArguments = new ArrayList<>(PtyBootstrap.MAX_ENTRY_COUNT + 1);
        tooManyArguments.add("/bin/true");
        for (int index = 0; index < PtyBootstrap.MAX_ENTRY_COUNT; index++) {
            tooManyArguments.add("");
        }
        IOException countFailure =
                assertThrows(IOException.class, () -> PtyBootstrap.prepare(payload(tooManyArguments, Map.of())));

        assertFalse(fieldFailure.getMessage().contains("secret-"));
        assertTrue(fieldFailure.getMessage().contains("field"));
        assertTrue(totalFailure.getMessage().contains("total"));
        assertTrue(countFailure.getMessage().contains("too many entries"));
    }

    @Test
    void bootstrapDeadlineScalesMonotonicallyAcrossEntryAndByteLimits() throws Exception {
        PtyBootstrap.Prepared minimal = PtyBootstrap.prepare(payload(List.of("/bin/true"), Map.of()));

        LinkedHashMap<String, String> intermediateEnvironment = new LinkedHashMap<>();
        for (int index = 0; index < PtyBootstrap.MAX_ENTRY_COUNT / 2; index++) {
            intermediateEnvironment.put("MID_" + index, "v");
        }
        PtyBootstrap.Prepared intermediateEntries =
                PtyBootstrap.prepare(payload(List.of("/bin/true"), intermediateEnvironment));

        ArrayList<String> command = new ArrayList<>(PtyBootstrap.MAX_ENTRY_COUNT);
        command.add("/bin/true");
        while (command.size() < PtyBootstrap.MAX_ENTRY_COUNT) {
            command.add("");
        }
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        while (environment.size() < PtyBootstrap.MAX_ENTRY_COUNT) {
            environment.put("ENTRY_" + environment.size(), "v");
        }
        PtyBootstrap.Prepared maximumEntries = PtyBootstrap.prepare(payload(command, environment));

        PtyBootstrap.Prepared intermediateBytes =
                PtyBootstrap.prepare(payload(List.of("/bin/true", "x".repeat(PtyBootstrap.MAX_FIELD_BYTES)), Map.of()));
        int remainingBytes = PtyBootstrap.MAX_TOTAL_BYTES - "/bin/true".length();
        ArrayList<String> maximumByteCommand = new ArrayList<>();
        maximumByteCommand.add("/bin/true");
        while (remainingBytes > 0) {
            int chunk = Math.min(remainingBytes, PtyBootstrap.MAX_FIELD_BYTES);
            maximumByteCommand.add("x".repeat(chunk));
            remainingBytes -= chunk;
        }
        PtyBootstrap.Prepared maximumBytes = PtyBootstrap.prepare(payload(maximumByteCommand, Map.of()));

        assertTrue(minimal.timeout().compareTo(PtyBootstrap.MIN_BOOTSTRAP_TIMEOUT) >= 0);
        assertTrue(intermediateEntries.timeout().compareTo(minimal.timeout()) > 0);
        assertTrue(intermediateBytes.timeout().compareTo(minimal.timeout()) > 0);
        assertEquals(PtyBootstrap.MAX_BOOTSTRAP_TIMEOUT, maximumEntries.timeout());
        assertEquals(PtyBootstrap.MAX_BOOTSTRAP_TIMEOUT, maximumBytes.timeout());
    }

    @Test
    void successfulHandshakeWritesTheBoundedFrameAndLeavesTargetStdinOpen() throws Exception {
        PtyTestProcess process = PtyTestProcess.completedWithOutput(143, HANDSHAKE);
        PtyBootstrap.Prepared bootstrap = PtyBootstrap.prepare(
                payload(List.of("/bin/true", ""), Map.of("EXACT", "line\n-option=value ' $(data-only)")));
        try {
            Process initialized = PtyLaunchAdmission.launch(Duration.ofSeconds(1), context -> {
                context.registerProcess(process);
                return bootstrap.initialize(process, context);
            });

            assertSame(process, initialized);
            assertTrue(process.stdinBytes().length > 0);
            assertFalse(process.stdinClosed(), "the target must retain the bootstrap stdin channel");
            assertEquals(1, process.stdinGetCount());
            assertEquals(1, process.stdoutGetCount());
            assertEquals(0, process.stderrGetCount());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void timeoutTerminatesThePartialProcessAndClosesItsStreams() throws Exception {
        PtyTestProcess process = PtyTestProcess.hanging();
        PtyBootstrap.Prepared bootstrap = PtyBootstrap.prepare(payload(List.of("/bin/true"), Map.of()));
        IOException failure = assertThrows(
                IOException.class,
                () -> PtyLaunchAdmission.launch(Duration.ofMillis(25), context -> {
                    context.registerProcess(process);
                    return bootstrap.initialize(process, context);
                }));

        assertTrue(failure.getMessage().contains("deadline"));
        assertTrue(process.destroyed());
        assertFalse(process.isAlive());
        assertTrue(process.streamsClosed());
    }

    @Test
    void interruptionIsRestoredAndTerminatesThePartialProcess() throws Exception {
        PtyTestProcess process = PtyTestProcess.hanging();
        PtyBootstrap.Prepared bootstrap = PtyBootstrap.prepare(payload(List.of("/bin/true"), Map.of()));
        Thread caller = Thread.currentThread();
        CountDownLatch registered = new CountDownLatch(1);
        Thread interrupter = new Thread(() -> {
            try {
                assertTrue(registered.await(1, TimeUnit.SECONDS));
                caller.interrupt();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        interrupter.start();
        try {
            assertThrows(
                    InterruptedException.class,
                    () -> PtyLaunchAdmission.launch(Duration.ofSeconds(1), context -> {
                        context.registerProcess(process);
                        registered.countDown();
                        return bootstrap.initialize(process, context);
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!process.destroyed() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(process.destroyed());
            assertFalse(process.isAlive());
        } finally {
            Thread.interrupted();
            interrupter.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(interrupter.isAlive());
        }
    }

    private static SystemPtyProvider.PtyPayload payload(List<String> command, Map<String, String> environment) {
        return new SystemPtyProvider.PtyPayload(command, environment);
    }
}
