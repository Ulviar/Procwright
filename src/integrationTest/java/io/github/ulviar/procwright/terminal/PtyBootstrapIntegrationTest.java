/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

final class PtyBootstrapIntegrationTest {

    private static final byte[] READY = "\u001ePROCWRIGHT_PTY_READY\u001f".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STARTED = "\u001ePROCWRIGHT_PTY_STARTED\u001f".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path temporaryDirectory;

    @Test
    void macOsBsdCommandFormLaunchesWithFixedSystemHelpers() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"));
        assertSuccessful(new SystemPtyProvider(macOsSupport()).start(request(List.of("/usr/bin/true"))));
    }

    @Test
    void exactMaximumEntryCountLaunchesOnTheDetectedSystemPty() throws Exception {
        SystemPtyProvider provider = provider();
        ArrayList<String> command = new ArrayList<>(PtyBootstrap.MAX_ENTRY_COUNT);
        command.add("/usr/bin/true");
        while (command.size() < PtyBootstrap.MAX_ENTRY_COUNT) {
            command.add("");
        }
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("TERM", "dumb");
        environment.put("COLUMNS", "80");
        environment.put("LINES", "24");
        while (environment.size() < PtyBootstrap.MAX_ENTRY_COUNT) {
            environment.put("EXACT_LIMIT_" + environment.size(), "v");
        }

        PtyRequest request = request(command, environment);
        SystemPtyProvider.PtyLaunchPlan plan = provider.planFor(request);
        assertEquals(PtyBootstrap.MAX_ENTRY_COUNT, plan.payload().command().size());
        assertEquals(PtyBootstrap.MAX_ENTRY_COUNT, plan.payload().environment().size());
        assertSuccessful(provider.start(request));
    }

    @Test
    void exactMaximumPayloadBytesLaunchOnTheDetectedSystemPty() throws Exception {
        SystemPtyProvider provider = provider();
        ArrayList<String> command = new ArrayList<>();
        command.add("/usr/bin/true");
        SystemPtyProvider.PtyLaunchPlan initial = provider.planFor(request(command));
        int remaining = PtyBootstrap.MAX_TOTAL_BYTES - payloadBytes(initial.payload());
        while (remaining > 0) {
            int chunk = Math.min(remaining, PtyBootstrap.MAX_FIELD_BYTES);
            command.add("x".repeat(chunk));
            remaining -= chunk;
        }
        PtyRequest request = request(command);

        assertEquals(
                PtyBootstrap.MAX_TOTAL_BYTES,
                payloadBytes(provider.planFor(request).payload()));
        assertSuccessful(provider.start(request));
    }

    @Test
    void failedTerminalSizingAbortsBeforeTheStartedHandshake() throws Exception {
        Path stty = fakeStty("fail");

        assertBootstrapSizingFailure(stty);
    }

    @Test
    void mismatchedTerminalSizingAbortsBeforeTheStartedHandshake() throws Exception {
        Path stty = fakeStty("mismatch");

        assertBootstrapSizingFailure(stty);
    }

    @Test
    void malformedPartialAndOverlimitFramesFailWithoutEchoingPayloadData() throws Exception {
        SystemPtyProvider.SystemPtySupport support = detectedSupport();
        String secret = "FRAME-SECRET-74f31c";
        List<byte[]> invalidFrames = List.of(
                "BADMAGIC".getBytes(StandardCharsets.US_ASCII),
                "PROCW3!!131073".getBytes(StandardCharsets.US_ASCII),
                "PROCW3!!000000257".getBytes(StandardCharsets.US_ASCII),
                "PROCW3!!03276900000132769".getBytes(StandardCharsets.US_ASCII),
                "PROCW3!!00000100000100000".getBytes(StandardCharsets.US_ASCII),
                frameWithDuplicateEnvironment(),
                frameWithInvalidEnd(secret));

        for (byte[] invalidFrame : invalidFrames) {
            Process process = startUninitialized(support);
            try {
                assertArrayEquals(READY, process.getInputStream().readNBytes(READY.length));
                process.getOutputStream().write(invalidFrame);
                process.getOutputStream().flush();
                process.getOutputStream().close();

                assertTrue(process.waitFor(3, TimeUnit.SECONDS), "malformed bootstrap frame did not fail");
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertNotEquals(0, process.exitValue());
                assertFalse(output.contains(new String(STARTED, StandardCharsets.US_ASCII)));
                assertFalse(output.contains(secret), "terminal echo exposed framed payload data");
            } finally {
                PtyProcessCleanup.terminate(process);
            }
        }

        Process partial = startUninitialized(support);
        try {
            assertArrayEquals(READY, partial.getInputStream().readNBytes(READY.length));
            partial.getOutputStream().write("PRO".getBytes(StandardCharsets.US_ASCII));
            partial.getOutputStream().flush();

            assertFalse(partial.waitFor(200, TimeUnit.MILLISECONDS), "partial frame unexpectedly launched a target");
        } finally {
            PtyProcessCleanup.terminate(partial);
        }
        assertFalse(partial.isAlive());
    }

    @Test
    @ResourceLock("java.io.tmpdir")
    void bootstrapCreatesNoControlFileInAHostileTemporaryDirectory() throws Exception {
        SystemPtyProvider.SystemPtySupport support = detectedSupport();
        assumeTrue(
                Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null,
                "hostile temporary directory fixture requires POSIX file attributes");
        Path hostileTemporaryDirectory = temporaryDirectory.resolve("world-writable-non-sticky");
        Files.createDirectory(hostileTemporaryDirectory);
        Files.setPosixFilePermissions(
                hostileTemporaryDirectory,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE,
                        PosixFilePermission.OTHERS_EXECUTE));
        String previous = System.getProperty("java.io.tmpdir");
        Process process = null;
        try {
            System.setProperty("java.io.tmpdir", hostileTemporaryDirectory.toString());
            process = new SystemPtyProvider(support).start(request(List.of("/usr/bin/true")));
            assertSuccessful(process);
            process = null;
        } finally {
            if (previous == null) {
                System.clearProperty("java.io.tmpdir");
            } else {
                System.setProperty("java.io.tmpdir", previous);
            }
            PtyProcessCleanup.terminate(process);
        }
        try (java.util.stream.Stream<Path> entries = Files.list(hostileTemporaryDirectory)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void optionLikeExecutableUsesTheCapabilityProbedEnvSeparator() throws Exception {
        Path executable = temporaryDirectory.resolve("-procwright-option-target");
        Files.writeString(executable, "#!/bin/sh\nprintf 'option-like-ok\\n'\n", StandardCharsets.US_ASCII);
        assertTrue(executable.toFile().setExecutable(true));
        PtyRequest request = new PtyRequest(
                List.of(executable.getFileName().toString()),
                Optional.of(temporaryDirectory),
                EnvironmentPolicy.CLEAN,
                Map.of("PATH", temporaryDirectory.toString()),
                new TerminalSize(80, 24));

        Process process = provider().start(request);
        try {
            assertTrue(process.waitFor(3, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("option-like-ok"), output);
        } finally {
            PtyProcessCleanup.terminate(process);
        }
    }

    @Test
    void capabilityRejectsEnvVariantsWithoutSeparatorOrEnvironmentResetSemantics() throws Exception {
        SystemPtyProvider.SystemPtySupport detected = detectedSupport();
        Path rejectsSeparator = fakeEnv("reject-separator");
        Path ignoresReset = fakeEnv("ignore-reset");

        for (Path env : List.of(rejectsSeparator, ignoresReset)) {
            SystemPtyProvider.SystemTools tools = new SystemPtyProvider.SystemTools(
                    detected.scriptPath(), detected.shellPath(), detected.sttyPath(), env, detected.ddPath());

            assertFalse(new SystemPtyCapabilityProbe().supports(detected.flavor(), tools));
        }
    }

    private SystemPtyProvider provider() {
        return new SystemPtyProvider(detectedSupport());
    }

    private static SystemPtyProvider.SystemPtySupport detectedSupport() {
        SystemPtyProvider.SystemPtySupport support = SystemPtyProvider.SystemPtySupport.detect();
        assumeTrue(support.available(), "system PTY provider is unavailable: " + support.description());
        return support;
    }

    private static SystemPtyProvider.SystemPtySupport macOsSupport() {
        return new SystemPtyProvider.SystemPtySupport(
                SystemPtyProvider.ScriptFlavor.BSD,
                Path.of("/usr/bin/script"),
                Path.of("/bin/sh"),
                Path.of("/bin/stty"),
                Path.of("/usr/bin/env"),
                Path.of("/bin/dd"),
                "macOS BSD integration fixture");
    }

    private void assertBootstrapSizingFailure(Path stty) throws Exception {
        SystemPtyProvider.SystemPtySupport detected = SystemPtyProvider.SystemPtySupport.detect();
        assumeTrue(detected.available(), "system PTY provider is unavailable: " + detected.description());
        SystemPtyProvider.SystemPtySupport support = new SystemPtyProvider.SystemPtySupport(
                detected.flavor(),
                detected.scriptPath(),
                detected.shellPath(),
                stty,
                detected.envPath(),
                detected.ddPath(),
                "injected stty test provider");
        SystemPtyProvider provider = new SystemPtyProvider(support);

        CommandExecutionException failure =
                assertThrows(CommandExecutionException.class, () -> provider.start(request(List.of("/usr/bin/true"))));

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, failure.reason());
        assertFalse(String.valueOf(failure.getMessage()).contains("/usr/bin/true"));
    }

    private Path fakeStty(String mode) throws IOException {
        Path executable = temporaryDirectory.resolve("stty-" + mode);
        String sizing = mode.equals("fail") ? "exit 19" : "exit 0";
        String reportedSize = mode.equals("mismatch") ? "23 79" : "24 80";
        Files.writeString(
                executable,
                "#!/bin/sh\n"
                        + "case $1 in\n"
                        + "  -g) printf 'saved-state\\n'; exit 0 ;;\n"
                        + "  raw) exit 0 ;;\n"
                        + "  saved-state) exit 0 ;;\n"
                        + "  rows) " + sizing + " ;;\n"
                        + "  size) printf '" + reportedSize + "\\n'; exit 0 ;;\n"
                        + "  *) exit 20 ;;\n"
                        + "esac\n",
                StandardCharsets.US_ASCII);
        assertTrue(executable.toFile().setExecutable(true));
        return executable.toAbsolutePath();
    }

    private Path fakeEnv(String mode) throws IOException {
        Path executable = temporaryDirectory.resolve("env-" + mode);
        String body = mode.equals("reject-separator")
                ? "[ \"$2\" = -- ] && exit 64\nexit 65\n"
                : "[ \"$1\" = -i ] && shift\n[ \"$1\" = -- ] && shift\nexec /usr/bin/env \"$@\"\n";
        Files.writeString(executable, "#!/bin/sh\n" + body, StandardCharsets.US_ASCII);
        assertTrue(executable.toFile().setExecutable(true));
        return executable.toAbsolutePath();
    }

    private static Process startUninitialized(SystemPtyProvider.SystemPtySupport support) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(PtyBootstrap.commandFor(support, new TerminalSize(80, 24)));
        builder.environment().clear();
        builder.environment().putAll(SystemPtyProvider.wrapperEnvironmentFor(support));
        return builder.start();
    }

    private static byte[] frameWithInvalidEnd(String secret) throws IOException {
        byte[] executable = "/usr/bin/true".getBytes(StandardCharsets.US_ASCII);
        byte[] argument = secret.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.writeBytes("PROCW3!!".getBytes(StandardCharsets.US_ASCII));
        writeFixed(frame, executable.length + argument.length, 6);
        writeFixed(frame, 0, 3);
        writeFixed(frame, 2, 3);
        writeField(frame, executable);
        writeField(frame, argument);
        frame.writeBytes("BAD-END!".getBytes(StandardCharsets.US_ASCII));
        return frame.toByteArray();
    }

    private static byte[] frameWithDuplicateEnvironment() throws IOException {
        byte[] name = "DUPLICATE".getBytes(StandardCharsets.US_ASCII);
        byte[] firstValue = "first".getBytes(StandardCharsets.US_ASCII);
        byte[] secondValue = "second".getBytes(StandardCharsets.US_ASCII);
        byte[] executable = "/usr/bin/true".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.writeBytes("PROCW3!!".getBytes(StandardCharsets.US_ASCII));
        writeFixed(frame, name.length * 2 + firstValue.length + secondValue.length + executable.length, 6);
        writeFixed(frame, 2, 3);
        writeField(frame, name);
        writeField(frame, firstValue);
        writeField(frame, name);
        writeField(frame, secondValue);
        writeFixed(frame, 1, 3);
        writeField(frame, executable);
        frame.writeBytes("!!3WCORP".getBytes(StandardCharsets.US_ASCII));
        return frame.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream output, byte[] value) throws IOException {
        writeFixed(output, value.length, 5);
        output.write(value);
    }

    private static void writeFixed(ByteArrayOutputStream output, int value, int width) {
        String digits = Integer.toString(value);
        output.writeBytes("0".repeat(width - digits.length()).getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(digits.getBytes(StandardCharsets.US_ASCII));
    }

    private static PtyRequest request(List<String> command) {
        return request(command, Map.of());
    }

    private static PtyRequest request(List<String> command, Map<String, String> environment) {
        return new PtyRequest(
                command, Optional.empty(), EnvironmentPolicy.CLEAN, environment, new TerminalSize(80, 24));
    }

    private static int payloadBytes(SystemPtyProvider.PtyPayload payload) {
        Charset charset = Charset.forName(
                System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name()));
        int total = 0;
        for (Map.Entry<String, String> entry : payload.environment().entrySet()) {
            total += entry.getKey().getBytes(charset).length;
            total += entry.getValue().getBytes(charset).length;
        }
        for (String argument : payload.command()) {
            total += argument.getBytes(charset).length;
        }
        return total;
    }

    private static void assertSuccessful(Process process) throws Exception {
        try {
            assertTrue(process.waitFor(12, TimeUnit.SECONDS), "maximum PTY payload did not exit");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    return exception.toString();
                }
            });
        } finally {
            PtyProcessCleanup.terminate(process);
        }
    }
}
