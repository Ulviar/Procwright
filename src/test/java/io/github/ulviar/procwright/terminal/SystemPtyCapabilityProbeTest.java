/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SystemPtyCapabilityProbeTest {

    private static final byte[] READY = "\u001ePROCWRIGHT_PTY_READY\u001f".getBytes(StandardCharsets.US_ASCII);
    private static final SystemPtyProvider.SystemTools TOOLS = new SystemPtyProvider.SystemTools(
            Path.of("/usr/bin/script"),
            Path.of("/bin/sh"),
            Path.of("/usr/bin/stty"),
            Path.of("/usr/bin/env"),
            Path.of("/bin/dd"));

    @Test
    void exactProbeUsesSanitizedEnvironmentAndTheSelectedAbsoluteScript() {
        CapturingStarter starter = new CapturingStarter(PtyTestProcess.completed(73));
        SystemPtyCapabilityProbe probe = new SystemPtyCapabilityProbe(starter, Duration.ofSeconds(1));

        assertTrue(probe.supports(SystemPtyProvider.ScriptFlavor.UTIL_LINUX, TOOLS));

        assertEquals(TOOLS.scriptPath().toString(), starter.command().get(0));
        assertEquals(List.of("-q", "-e", "-c"), starter.command().subList(1, 4));
        assertEquals("/dev/null", starter.command().get(5));
        assertEquals(Map.of("SHELL", "/bin/sh", "LC_ALL", "C", "LANG", "C", "TERM", "dumb"), starter.environment());
        assertFalse(starter.environment().containsKey("PATH"));
        assertFalse(starter.environment().containsKey("SHELLOPTS"));
        assertFalse(starter.environment().containsKey("LD_PRELOAD"));
        assertTrue(starter.process().stdinBytes().length > 0, "the exact env/argv probe must use the framed channel");
    }

    @Test
    void successfulLookingOutputCannotReplaceExitPropagationProof() {
        byte[] spoofed = ("util-linux script from util-linux 2.99\n" + new String(READY, StandardCharsets.US_ASCII))
                .getBytes(StandardCharsets.US_ASCII);
        PtyTestProcess process = PtyTestProcess.completedWithOutput(0, spoofed);
        SystemPtyCapabilityProbe probe =
                new SystemPtyCapabilityProbe(new CapturingStarter(process), Duration.ofSeconds(1));

        assertFalse(probe.supports(SystemPtyProvider.ScriptFlavor.UTIL_LINUX, TOOLS));
        assertTrue(process.destroyed(), "a rejected probe process must be cleaned");
    }

    @Test
    void wrongChildExitCodeIsUnavailableEvenAfterAValidHandshake() {
        SystemPtyCapabilityProbe probe =
                new SystemPtyCapabilityProbe(new CapturingStarter(PtyTestProcess.completed(0)), Duration.ofSeconds(1));

        assertFalse(probe.supports(SystemPtyProvider.ScriptFlavor.BSD, TOOLS));
    }

    @Test
    void hugeProbeOutputIsReadOnlyToTheFixedHandshakeBound() {
        byte[] output = new byte[4 * 1024 * 1024];
        java.util.Arrays.fill(output, (byte) 'x');
        PtyTestProcess process = PtyTestProcess.completedWithOutput(0, output);
        SystemPtyCapabilityProbe probe =
                new SystemPtyCapabilityProbe(new CapturingStarter(process), Duration.ofSeconds(1));

        long started = System.nanoTime();
        assertFalse(probe.supports(SystemPtyProvider.ScriptFlavor.UTIL_LINUX, TOOLS));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 500, "bounded handshake consumed huge output for " + elapsedMillis + " ms");
        assertTrue(process.destroyed(), "a rejected huge-output probe must be cleaned");
    }

    @Test
    void hangingProbeTimesOutRestoresCapacityAndCleansTheProcess() {
        PtyTestProcess process = PtyTestProcess.hanging();
        SystemPtyCapabilityProbe probe =
                new SystemPtyCapabilityProbe(new CapturingStarter(process), Duration.ofMillis(50));

        long started = System.nanoTime();
        assertFalse(probe.supports(SystemPtyProvider.ScriptFlavor.UTIL_LINUX, TOOLS));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 750, "hanging probe exceeded its bounded cleanup window");
        assertTrue(process.destroyed());
        assertFalse(process.isAlive());
    }

    @Test
    void interruptedProbeRestoresInterruptionAndCleansTheProcess() {
        PtyTestProcess process = PtyTestProcess.hanging();
        SystemPtyCapabilityProbe probe = new SystemPtyCapabilityProbe(
                builder -> {
                    Thread.currentThread().interrupt();
                    return process;
                },
                Duration.ofSeconds(1));
        try {
            assertFalse(probe.supports(SystemPtyProvider.ScriptFlavor.BSD, TOOLS));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(process.destroyed());
        } finally {
            Thread.interrupted();
        }
    }

    private static final class CapturingStarter implements SystemPtyCapabilityProbe.ProcessStarter {

        private final PtyTestProcess process;
        private List<String> command = List.of();
        private Map<String, String> environment = Map.of();

        private CapturingStarter(PtyTestProcess process) {
            this.process = process;
        }

        @Override
        public Process start(ProcessBuilder builder) {
            command = List.copyOf(builder.command());
            environment = Map.copyOf(builder.environment());
            return process;
        }

        private List<String> command() {
            return command;
        }

        private Map<String, String> environment() {
            return environment;
        }

        private PtyTestProcess process() {
            return process;
        }
    }
}
