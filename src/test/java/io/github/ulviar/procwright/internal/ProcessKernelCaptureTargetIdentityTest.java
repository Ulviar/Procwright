/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.OutputMode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessKernelCaptureTargetIdentityTest extends ProcessKernelTestSupport {

    @Test
    void hardLinkedCaptureTargetsAreRejectedBeforeProcessLaunch(@TempDir Path directory) throws IOException {
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("stderr.log");
        Files.writeString(stdout, "unchanged");
        Files.createLink(stderr, stdout);
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertTrue(failure.getMessage().contains("distinct"));
        assertEquals(0, starts.get());
        assertEquals("unchanged", Files.readString(stdout));
        assertEquals("unchanged", Files.readString(stderr));
    }

    @Test
    void symlinkedCaptureTargetsAreRejectedBeforeProcessLaunch(@TempDir Path directory) throws IOException {
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("stderr.log");
        Files.writeString(stdout, "unchanged");
        try {
            Files.createSymbolicLink(stderr, stdout.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable: " + unsupported);
        }
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertEquals(0, starts.get());
        assertEquals("unchanged", Files.readString(stdout));
        assertEquals("unchanged", Files.readString(stderr));
    }

    @Test
    void aliasedParentDirectoriesForNewCaptureTargetsAreRejectedBeforeLaunch(@TempDir Path directory)
            throws IOException {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path aliasDirectory = directory.resolve("alias");
        try {
            Files.createSymbolicLink(aliasDirectory, realDirectory.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable: " + unsupported);
        }
        Path stdout = realDirectory.resolve("output.log");
        Path stderr = aliasDirectory.resolve("output.log");
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertEquals(0, starts.get());
        assertFalse(Files.exists(stdout));
    }

    @Test
    void portableCaseAliasesForNewCaptureTargetsAreRejectedBeforeLaunch(@TempDir Path directory) {
        Path stdout = directory.resolve("Capture.log");
        Path stderr = directory.resolve("capture.log");
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertEquals(0, starts.get());
        assertFalse(Files.exists(stdout));
        assertFalse(Files.exists(stderr));
    }

    @Test
    void portableUnicodeAliasesForNewCaptureTargetsAreRejectedBeforeLaunch(@TempDir Path directory) {
        Path stdout = directory.resolve("\u00e9.log");
        Path stderr = directory.resolve("e\u0301.log");
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertEquals(0, starts.get());
        assertFalse(Files.exists(stdout));
        assertFalse(Files.exists(stderr));
    }

    @Test
    void trailingDotAndSpaceAliasesForNewCaptureTargetsAreRejectedBeforeLaunch(@TempDir Path directory)
            throws IOException {
        URI archive =
                URI.create("jar:" + directory.resolve("portable-paths.zip").toUri());
        try (FileSystem paths = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path stdout = paths.getPath("/capture.log");
            Path stderr = paths.getPath("/capture.log. ");
            AtomicInteger starts = new AtomicInteger();
            ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
                starts.incrementAndGet();
                throw new AssertionError("capture validation must run before process launch");
            });

            assertThrows(
                    IllegalArgumentException.class,
                    () -> kernel.run(executionPlan(
                            CapturePolicy.toPath(stdout, stderr),
                            DiagnosticsSettings.disabled(),
                            Optional.empty(),
                            OutputMode.SEPARATE,
                            Duration.ofSeconds(1))));

            assertEquals(0, starts.get());
            assertFalse(Files.exists(stdout));
            assertFalse(Files.exists(stderr));
        }
    }

    @Test
    void mixedExistingAndNewPortableAliasesAreRejectedWithoutTruncation(@TempDir Path directory) throws IOException {
        Path stdout = directory.resolve("Capture.log");
        Path stderr = directory.resolve("capture.log");
        Files.writeString(stdout, "unchanged");
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertEquals(0, starts.get());
        assertEquals("unchanged", Files.readString(stdout));
    }

    @Test
    void existingPortableAliasesAreRejectedEvenWhenTheFilesystemKeepsThemDistinct(@TempDir Path directory)
            throws IOException {
        Path stdout = directory.resolve("Capture.log");
        Path stderr = directory.resolve("capture.log");
        Files.writeString(stdout, "stdout-unchanged");
        org.junit.jupiter.api.Assumptions.assumeFalse(
                Files.exists(stderr) && Files.isSameFile(stdout, stderr),
                "filesystem does not keep case variants distinct");
        Files.writeString(stderr, "stderr-unchanged");
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertEquals(0, starts.get());
        assertEquals("stdout-unchanged", Files.readString(stdout));
        assertEquals("stderr-unchanged", Files.readString(stderr));
    }

    @Test
    void danglingSymlinksToTheSameNewCaptureTargetAreRejectedBeforeLaunch(@TempDir Path directory) throws IOException {
        Path stdout = directory.resolve("stdout-link");
        Path stderr = directory.resolve("stderr-link");
        try {
            Files.createSymbolicLink(stdout, Path.of("target.log"));
            Files.createSymbolicLink(stderr, Path.of("target.log"));
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable: " + unsupported);
        }
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertEquals(0, starts.get());
        assertFalse(Files.exists(directory.resolve("target.log")));
    }

    @Test
    void distinctNewTargetsThroughAnAliasedParentReachProcessLaunch(@TempDir Path directory) throws IOException {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path aliasDirectory = directory.resolve("alias");
        try {
            Files.createSymbolicLink(aliasDirectory, realDirectory.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable: " + unsupported);
        }
        AssertionError marker = new AssertionError("process launch reached");
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw marker;
        });

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(realDirectory.resolve("stdout.log"), aliasDirectory.resolve("stderr.log")),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertSame(marker, thrown);
        assertEquals(1, starts.get());
    }

    @Test
    void cyclicCaptureSymlinkFailsClosedBeforeProcessLaunch(@TempDir Path directory) throws IOException {
        Path first = directory.resolve("first-link");
        Path second = directory.resolve("second-link");
        try {
            Files.createSymbolicLink(first, second.getFileName());
            Files.createSymbolicLink(second, first.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable: " + unsupported);
        }
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(first, directory.resolve("stderr.log")),
                        DiagnosticsSettings.disabled(),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofSeconds(1))));

        assertEquals(0, starts.get());
    }

    @Test
    void indeterminateCaptureIdentityFailsBeforeLaunchWithoutTruncatingOutput(@TempDir Path directory)
            throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                directory.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are unavailable");
        Path stdout = directory.resolve("stdout.log");
        Files.writeString(stdout, "unchanged");
        Path restricted = Files.createDirectory(directory.resolve("restricted"));
        Path stderr = restricted.resolve("stderr.log");
        Files.createLink(stderr, stdout);
        Set<java.nio.file.attribute.PosixFilePermission> originalPermissions =
                Files.getPosixFilePermissions(restricted);
        Files.setPosixFilePermissions(restricted, Set.of());
        try {
            boolean identityIsIndeterminate;
            try {
                Files.readAttributes(stderr, java.nio.file.attribute.BasicFileAttributes.class);
                identityIsIndeterminate = false;
            } catch (IOException | SecurityException expected) {
                identityIsIndeterminate = true;
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    identityIsIndeterminate, "test user can still inspect a directory without search permission");
            AtomicInteger starts = new AtomicInteger();
            ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
                starts.incrementAndGet();
                throw new AssertionError("capture validation must run before process launch");
            });

            assertThrows(
                    IllegalArgumentException.class,
                    () -> kernel.run(executionPlan(
                            CapturePolicy.toPath(stdout, stderr),
                            DiagnosticsSettings.disabled(),
                            Optional.empty(),
                            OutputMode.SEPARATE,
                            Duration.ofSeconds(1))));
            assertEquals(0, starts.get());
        } finally {
            Files.setPosixFilePermissions(restricted, originalPermissions);
        }
        assertEquals("unchanged", Files.readString(stdout));
    }
}
