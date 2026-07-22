/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessKernelLifecycleTest {

    @Test
    void elapsedDurationUsesInjectedMonotonicTimeAndClampsBackwardReadings() {
        AtomicLong nanoTime = new AtomicLong(100);
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream());
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofSeconds(1),
                () -> nanoTime.getAndSet(50));

        CommandResult result = kernel.run(executionPlan(
                DiagnosticsSettings.disabled(), StdinPolicy.closed(), OutputMode.SEPARATE, Duration.ofSeconds(1)));

        assertEquals(Duration.ZERO, result.elapsed());
    }

    @Test
    void hardLinkedCaptureTargetsAreRejectedBeforeProcessLaunch(@TempDir Path directory) throws IOException {
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("stderr.log");
        Files.writeString(stdout, "unchanged");
        Files.createLink(stderr, stdout);
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
            ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
                starts.incrementAndGet();
                throw new AssertionError("capture validation must run before process launch");
            });

            assertThrows(
                    IllegalArgumentException.class,
                    () -> kernel.run(executionPlan(
                            CapturePolicy.toPath(stdout, stderr),
                            DiagnosticsSettings.disabled(),
                            StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(stdout, stderr),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw marker;
        });

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(realDirectory.resolve("stdout.log"), aliasDirectory.resolve("stderr.log")),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            starts.incrementAndGet();
            throw new AssertionError("capture validation must run before process launch");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.run(executionPlan(
                        CapturePolicy.toPath(first, directory.resolve("stderr.log")),
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
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
            ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
                starts.incrementAndGet();
                throw new AssertionError("capture validation must run before process launch");
            });

            assertThrows(
                    IllegalArgumentException.class,
                    () -> kernel.run(executionPlan(
                            CapturePolicy.toPath(stdout, stderr),
                            DiagnosticsSettings.disabled(),
                            StdinPolicy.closed(),
                            OutputMode.SEPARATE,
                            Duration.ofSeconds(1))));
            assertEquals(0, starts.get());
        } finally {
            Files.setPosixFilePermissions(restricted, originalPermissions);
        }
        assertEquals("unchanged", Files.readString(stdout));
    }

    @Test
    void earlyStdinFailureWinsBeforeLongDeadlineAndStopsTheLiveProcess() throws Exception {
        for (Throwable expected :
                List.of(new IllegalStateException("stdin writer failed"), new AssertionError("stdin writer failed"))) {
            ImmediateFailingOutputStream stdin = new ImmediateFailingOutputStream(expected);
            TerminalProcess process =
                    new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin, true);
            CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
            ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process);
            FutureTask<Throwable> execution = new FutureTask<>(() -> captureFailure(() -> kernel.run(executionPlan(
                    DiagnosticsSettings.disabled().withListener(events::add),
                    StdinPolicy.input(CommandInput.bytes(new byte[8 * 1024 * 1024])),
                    OutputMode.SEPARATE,
                    Duration.ofSeconds(30)))));
            Thread worker = new Thread(execution, "one-shot-early-stdin-failure-test");
            worker.setDaemon(true);
            worker.start();

            Throwable actual = execution.get(1, TimeUnit.SECONDS);

            if (expected instanceof Error) {
                assertSame(expected, actual);
            } else {
                CommandExecutionException typed = (CommandExecutionException) actual;
                assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, typed.reason());
                assertSame(expected, typed.getCause());
            }
            assertFalse(process.isAlive());
            assertEquals(0, terminalCount(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(1, stdin.closeCalls());
        }
    }

    @Test
    void exhaustedOneShotTaskCapacityRejectsBeforeStartingAnotherProcessAndRecoversAfterActualExit() throws Exception {
        OneShotIoTaskOwner owner = new OneShotIoTaskOwner(1);
        NonCooperativeOutputStream firstStdin = new NonCooperativeOutputStream();
        TerminalProcess first =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), firstStdin, true);
        AtomicInteger starts = new AtomicInteger();
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> {
                    if (starts.incrementAndGet() == 1) {
                        return first;
                    }
                    return new TerminalProcess(
                            new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream());
                },
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofMillis(50),
                owner);
        ExecutionPlan plan = executionPlan(
                CapturePolicy.discard(),
                DiagnosticsSettings.disabled(),
                StdinPolicy.input(CommandInput.bytes(new byte[] {1})),
                OutputMode.SEPARATE,
                Duration.ofMillis(10));
        try {
            assertThrows(CommandExecutionException.class, () -> kernel.run(plan));
            assertEquals(0, owner.availablePermits());

            CommandExecutionException exhausted = assertThrows(CommandExecutionException.class, () -> kernel.run(plan));

            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, exhausted.reason());
            assertTrue(exhausted.getMessage().contains("bounded I/O task capacity"));
            assertEquals(1, starts.get(), "capacity rejection must precede process launch");

            firstStdin.release.countDown();
            assertTrue(eventually(() -> owner.availablePermits() == 1));
            kernel.run(plan);
            assertEquals(2, starts.get());
        } finally {
            firstStdin.release.countDown();
        }
    }

    @Test
    void failureCleanupForceStopsRootWhenLivenessCannotBeObserved() {
        LivenessRestrictedProcess process = new LivenessRestrictedProcess();
        IllegalStateException primaryFailure = new IllegalStateException("primary failure");

        ProcessKernel.forceStopAfterFailure(process, Set.of(), primaryFailure);

        assertEquals(1, process.forceDestroyCalls());
        assertTrue(primaryFailure.getSuppressed().length == 0);
    }

    @Test
    void cleanupContinuesWhenDestroyThrowsThePrimaryFailureItself() throws Exception {
        AssertionError primaryFailure = new AssertionError("primary failure");
        SelfFailingCleanupProcess process = new SelfFailingCleanupProcess(primaryFailure);

        ProcessKernel.forceStopAfterFailure(process, Set.of(), primaryFailure);

        assertTrue(eventually(() -> process.stdoutClosed() && process.stderrClosed()));
        assertEquals(0, primaryFailure.getSuppressed().length);
    }

    @Test
    void failureCleanupDispatchesExactlyOnePhysicalStdinClose() throws Exception {
        assertTrue(eventually(() -> BoundedCloseDispatcher.shared().outstandingCount() == 0));
        int baselineOutstanding = BoundedCloseDispatcher.shared().outstandingCount();
        BlockingCloseOutputStream stdin = new BlockingCloseOutputStream();
        CleanupProcess process = new CleanupProcess(stdin);
        AssertionError primaryFailure = new AssertionError("primary failure");
        try {
            ProcessKernel.forceStopAfterFailure(process, Set.of(), primaryFailure);

            assertTrue(stdin.awaitClose());
            assertEquals(1, stdin.closeCalls());
            assertTrue(BoundedCloseDispatcher.shared().outstandingCount() >= baselineOutstanding + 1);
            assertFalse(process.isAlive());
            assertEquals(0, primaryFailure.getSuppressed().length);
        } finally {
            stdin.release();
        }
        assertTrue(eventually(() -> BoundedCloseDispatcher.shared().outstandingCount() == baselineOutstanding));
    }

    @Test
    void asynchronousStdinCloseFailureIsSuppressedOnTheOperationPrimaryExactlyOnce() throws Exception {
        AssertionError closeFailure = new AssertionError("stdin close failed");
        CloseCountingOutputStream stdin = new CloseCountingOutputStream(closeFailure);
        CleanupProcess process = new CleanupProcess(stdin);
        AssertionError primaryFailure = new AssertionError("primary failure");

        ProcessKernel.forceStopAfterFailure(process, Set.of(), primaryFailure);

        assertTrue(stdin.awaitClose());
        assertEquals(1, stdin.closeCalls());
        assertTrue(eventually(() -> primaryFailure.getSuppressed().length == 1));
        assertSame(closeFailure, primaryFailure.getSuppressed()[0]);
    }

    @Test
    void launchErrorRetainsIdentityAndEmitsOneSafeProcessFailure() throws Exception {
        AssertionError launchFailure = new AssertionError("launch failed");
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch failureDelivered = new CountDownLatch(1);
        ExecutionPlan plan = executionPlan(
                StandardCharsets.UTF_8, DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        failureDelivered.countDown();
                    }
                }));
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> {
            throw launchFailure;
        });

        AssertionError thrown = assertThrows(AssertionError.class, () -> kernel.run(plan));

        assertSame(launchFailure, thrown);
        assertTrue(failureDelivered.await(1, TimeUnit.SECONDS));
        List<DiagnosticEvent> failures = events.stream()
                .filter(event -> event.type() == DiagnosticEventType.PROCESS_FAILED)
                .toList();
        assertEquals(1, failures.size());
        assertEquals(
                AssertionError.class.getName(), failures.get(0).attributes().get("error"));
    }

    @Test
    void postStartErrorRetainsIdentityWhenDiagnosticListenerAlsoFails() throws Exception {
        AssertionError operationFailure = new AssertionError("post-start failed");
        CountDownLatch listenerCalled = new CountDownLatch(1);
        CleanupProcess process = new CleanupProcess(new CloseCountingOutputStream(null));
        ExecutionPlan plan = executionPlan(
                StandardCharsets.UTF_8, DiagnosticsSettings.disabled().withListener(event -> {
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        listenerCalled.countDown();
                        throw new AssertionError("listener failed");
                    }
                }));
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {
                    throw operationFailure;
                },
                (launchPlan, stdio) -> process);

        AssertionError thrown = assertThrows(AssertionError.class, () -> kernel.run(plan));

        assertSame(operationFailure, thrown);
        assertTrue(listenerCalled.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    @Test
    void decodeFailureDoesNotCallHostileDisplayNameOrMaskOriginalCause() {
        IllegalStateException decoderFailure = new IllegalStateException("decoder failed");
        AssertionError displayNameFailure = new AssertionError("displayName must not be called");
        HostileDisplayNameCharset charset = new HostileDisplayNameCharset(decoderFailure, displayNameFailure);
        ExecutionPlan plan = executionPlan(charset);

        CommandExecutionException thrown = assertThrows(
                CommandExecutionException.class,
                () -> ProcessKernel.decodeCapturedOutputs(
                        new CapturedOutput(new byte[] {'A'}, false),
                        new CapturedOutput(new byte[] {'B'}, false),
                        plan,
                        OptionalInt.of(0),
                        false,
                        Duration.ofMillis(1)));

        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, thrown.reason());
        assertSame(decoderFailure, thrown.getCause());
        assertEquals(0, charset.displayNameCalls());
        assertEquals("A", thrown.result().orElseThrow().stdout());
        assertEquals("B", thrown.result().orElseThrow().stderr());
        assertEquals(List.of((byte) 'A'), boxed(thrown.result().orElseThrow().stdoutBytes()));
        assertEquals(List.of((byte) 'B'), boxed(thrown.result().orElseThrow().stderrBytes()));
    }

    @Test
    void decodeFailureElapsedIsSampledAfterBlockedSupervisionCleanup() throws Exception {
        AtomicBoolean cleanupFinished = new AtomicBoolean();
        BlockingCleanupInputStream stdout = new BlockingCleanupInputStream(cleanupFinished);
        TerminalProcess process = new TerminalProcess(stdout, new TrackingInputStream(), new TrackingOutputStream());
        AtomicInteger nanoReads = new AtomicInteger();
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofSeconds(1),
                () -> nanoReads.getAndIncrement() == 0 ? 100L : cleanupFinished.get() ? 500L : 200L);
        ExecutionPlan plan = executionPlan(
                DiagnosticsSettings.disabled(), StdinPolicy.closed(), OutputMode.SEPARATE, Duration.ofSeconds(1));
        FutureTask<Throwable> execution = new FutureTask<>(() -> captureFailure(() -> kernel.run(plan)));
        Thread runner = new Thread(execution, "procwright-decode-cleanup-elapsed-test");
        runner.setDaemon(true);
        runner.start();
        try {
            assertTrue(stdout.awaitClose());
            assertFalse(execution.isDone(), "decode failure must wait for physical supervision cleanup");
        } finally {
            stdout.releaseClose();
        }

        CommandExecutionException failure = (CommandExecutionException) execution.get(1, TimeUnit.SECONDS);
        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, failure.reason());
        assertEquals(Duration.ofNanos(400), failure.result().orElseThrow().elapsed());
        assertTrue(cleanupFinished.get());
    }

    @Test
    void asynchronousStdoutAndStderrErrorsRetainIdentityAndOwnCloseFailures() throws Exception {
        for (boolean stdoutFails : new boolean[] {true, false}) {
            AssertionError readFailure = new AssertionError(stdoutFails ? "stdout read" : "stderr read");
            AssertionError closeFailure = new AssertionError(stdoutFails ? "stdout close" : "stderr close");
            FailingReadInputStream failing = new FailingReadInputStream(readFailure, closeFailure);
            TrackingInputStream other = new TrackingInputStream();
            TerminalProcess process = stdoutFails
                    ? new TerminalProcess(failing, other, new TrackingOutputStream())
                    : new TerminalProcess(other, failing, new TrackingOutputStream());
            List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
            ExecutionPlan plan = executionPlan(
                    DiagnosticsSettings.disabled().withListener(events::add),
                    StdinPolicy.closed(),
                    OutputMode.SEPARATE,
                    Duration.ofSeconds(1));
            ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process);

            AssertionError actual = assertThrows(AssertionError.class, () -> kernel.run(plan));

            assertSame(readFailure, actual);
            assertTrue(java.util.Arrays.asList(actual.getSuppressed()).contains(closeFailure));
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(
                    AssertionError.class.getName(),
                    events.stream()
                            .filter(event -> event.type() == DiagnosticEventType.PROCESS_FAILED)
                            .findFirst()
                            .orElseThrow()
                            .attributes()
                            .get("error"));
            assertEquals(1, failing.closeCalls());
            assertEquals(1, other.closeCalls());
            assertEquals(1, process.stdin.closeCalls());
        }
    }

    @Test
    void asynchronousStdinErrorRetainsIdentity() throws Exception {
        AssertionError writeFailure = new AssertionError("stdin write");
        AssertionError closeFailure = new AssertionError("stdin close");
        FailingWriteOutputStream stdin = new FailingWriteOutputStream(writeFailure, closeFailure);
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin, true);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ExecutionPlan plan = executionPlan(
                DiagnosticsSettings.disabled().withListener(events::add),
                StdinPolicy.input(CommandInput.text("payload", StandardCharsets.UTF_8)),
                OutputMode.SEPARATE,
                Duration.ofSeconds(1));
        ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process);

        AssertionError actual = assertThrows(AssertionError.class, () -> kernel.run(plan));

        assertSame(writeFailure, actual);
        assertTrue(java.util.Arrays.asList(actual.getSuppressed()).contains(closeFailure));
        assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
        assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        assertEquals(1, stdin.closeCalls());
    }

    @Test
    void successfulCaptureClosesEveryStableStreamExactlyOnceInSeparateAndMergedModes() {
        for (OutputMode outputMode : OutputMode.values()) {
            TrackingOutputStream stdin = new TrackingOutputStream();
            TrackingInputStream stdout = new TrackingInputStream();
            TrackingInputStream stderr = new TrackingInputStream();
            TerminalProcess process = new TerminalProcess(stdout, stderr, stdin);
            ProcessKernel kernel = new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process);

            kernel.run(executionPlan(
                    DiagnosticsSettings.disabled(), StdinPolicy.closed(), outputMode, Duration.ofSeconds(1)));

            assertEquals(1, stdin.closeCalls());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
            assertEquals(1, process.stdinGets.get());
            assertEquals(1, process.stdoutGets.get());
            assertEquals(1, process.stderrGets.get());
        }
    }

    @Test
    void exhaustedCloseCapacityFailsBeforeOneShotProcessPublicationOrStreamObservation() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        BoundedCloseDispatcher.Reservation occupied = dispatcher.reserve(3);
        TerminalProcess process = new TerminalProcess(
                new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel =
                new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(1));
        try {
            assertThrows(
                    RejectedExecutionException.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            StdinPolicy.closed(),
                            OutputMode.SEPARATE,
                            Duration.ofMillis(10))));

            assertFalse(process.isAlive());
            assertEquals(0, process.stdinGets.get());
            assertEquals(0, process.stdoutGets.get());
            assertEquals(0, process.stderrGets.get());
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_STARTED));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        } finally {
            occupied.release();
        }
    }

    @Test
    void timedOutRunConsumesItsPreReservedClosesAtFullDispatcherCapacity() {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        TerminalProcess process = new TerminalProcess(
                new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
        ProcessKernel kernel =
                new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(1));

        assertTrue(kernel.run(executionPlan(
                        DiagnosticsSettings.disabled(),
                        StdinPolicy.closed(),
                        OutputMode.SEPARATE,
                        Duration.ofMillis(10)))
                .timedOut());

        assertEquals(0, dispatcher.outstandingCount());
        assertEquals(1, process.stdin.closeCalls());
        assertEquals(1, process.stdout.closeCalls());
        assertEquals(1, process.stderr.closeCalls());
    }

    @Test
    void acceptedFallbackCloseKeepsOneShotCompletionPendingUntilPhysicalSettlement() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("stdin close starter failed");
        BlockingCloseOutputStream stdin = new BlockingCloseOutputStream();
        TerminalProcess process = new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3, (name, task) -> {
            if (name.contains("stdin")) {
                throw startFailure;
            }
            return Threading.start(name, task);
        });
        ProcessKernel kernel =
                new ProcessKernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(2));
        FutureTask<CommandResult> run = new FutureTask<>(() -> kernel.run(executionPlan(StandardCharsets.UTF_8)));
        Thread caller = new Thread(run, "procwright-kernel-blocked-fallback-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(stdin.awaitClose());
            assertFalse(
                    run.isDone(), "ProcessKernel.awaitClose must retain terminal publication until fallback settles");
        } finally {
            stdin.release();
        }

        java.util.concurrent.ExecutionException terminal =
                assertThrows(java.util.concurrent.ExecutionException.class, () -> run.get(1, TimeUnit.SECONDS));
        assertSame(startFailure, terminal.getCause());
        assertEquals(1, stdin.closeCalls());
        assertTrue(eventually(() ->
                dispatcher.activeCount() == 0 && dispatcher.pendingCount() == 0 && dispatcher.outstandingCount() == 0));
    }

    @Test
    void executorTerminationFailureBecomesPrimaryBeforeSuccessDiagnostics() throws Exception {
        NonCooperativeOutputStream stdin = new NonCooperativeOutputStream();
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin, true);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofMillis(50));
        try {
            CommandExecutionException failure = assertThrows(
                    CommandExecutionException.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            StdinPolicy.input(CommandInput.bytes(new byte[] {1})),
                            OutputMode.SEPARATE,
                            Duration.ofMillis(10))));

            assertTrue(failure.getMessage().contains("stopping command lifecycle tasks"));
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        } finally {
            stdin.release.countDown();
        }
    }

    @Test
    void executorTerminationFailureIsSuppressedOnceOnAnExistingFatalPrimary() throws Exception {
        AssertionError readFailure = new AssertionError("stdout failed while stdin writer remained active");
        NonCooperativeOutputStream stdin = new NonCooperativeOutputStream();
        TerminalProcess process =
                new TerminalProcess(new ReadErrorInputStream(readFailure), new TrackingInputStream(), stdin, true);
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = new ProcessKernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3, 6),
                Duration.ofMillis(50));
        try {
            AssertionError actual = assertThrows(
                    AssertionError.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            StdinPolicy.input(CommandInput.bytes(new byte[] {1})),
                            OutputMode.SEPARATE,
                            Duration.ofMillis(10))));

            assertSame(readFailure, actual);
            List<Throwable> terminationFailures = java.util.Arrays.stream(actual.getSuppressed())
                    .filter(CommandExecutionException.class::isInstance)
                    .filter(failure -> failure.getMessage().contains("stopping command lifecycle tasks"))
                    .toList();
            assertEquals(1, terminationFailures.size());
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        } finally {
            stdin.release.countDown();
        }
    }

    private static int terminalCount(List<DiagnosticEvent> events, DiagnosticEventType type) {
        return Math.toIntExact(
                events.stream().filter(event -> event.type() == type).count());
    }

    private static ExecutionPlan executionPlan(
            DiagnosticsSettings diagnostics, StdinPolicy stdin, OutputMode outputMode, Duration timeout) {
        return executionPlan(CapturePolicy.bounded(8), diagnostics, stdin, outputMode, timeout);
    }

    private static ExecutionPlan executionPlan(
            CapturePolicy capturePolicy,
            DiagnosticsSettings diagnostics,
            StdinPolicy stdin,
            OutputMode outputMode,
            Duration timeout) {
        return new ExecutionPlan(
                new LaunchPlan(
                        LaunchMode.DIRECT,
                        List.of("unused"),
                        Optional.empty(),
                        EnvironmentPolicy.INHERIT,
                        Map.of(),
                        outputMode,
                        TerminalPolicy.DISABLED),
                capturePolicy,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                timeout,
                CharsetPolicy.report(StandardCharsets.UTF_8),
                stdin,
                diagnostics);
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static ExecutionPlan executionPlan(Charset charset) {
        return executionPlan(charset, DiagnosticsSettings.disabled());
    }

    private static ExecutionPlan executionPlan(Charset charset, DiagnosticsSettings diagnostics) {
        return new ExecutionPlan(
                new LaunchPlan(
                        LaunchMode.DIRECT,
                        List.of("unused"),
                        Optional.empty(),
                        EnvironmentPolicy.INHERIT,
                        Map.of(),
                        OutputMode.SEPARATE,
                        TerminalPolicy.DISABLED),
                CapturePolicy.bounded(8),
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ofSeconds(1),
                CharsetPolicy.report(charset),
                StdinPolicy.closed(),
                diagnostics);
    }

    private static List<Byte> boxed(byte[] bytes) {
        java.util.ArrayList<Byte> boxed = new java.util.ArrayList<>(bytes.length);
        for (byte value : bytes) {
            boxed.add(value);
        }
        return boxed;
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class CleanupProcess extends Process {

        private final OutputStream stdin;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private CleanupProcess(OutputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            alive.set(false);
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class CloseCountingOutputStream extends OutputStream {

        private final Error failure;
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private CloseCountingOutputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
            if (failure != null) {
                throw failure;
            }
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        final int closeCalls() {
            return closes.get();
        }
    }

    private static final class BlockingCloseOutputStream extends TrackingOutputStream {

        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            super.close();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitClose() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static class TrackingInputStream extends InputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        final int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class BlockingCleanupInputStream extends TrackingInputStream {

        private final AtomicBoolean cleanupFinished;
        private final AtomicBoolean byteReturned = new AtomicBoolean();
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        private BlockingCleanupInputStream(AtomicBoolean cleanupFinished) {
            this.cleanupFinished = cleanupFinished;
        }

        @Override
        public int read() {
            return byteReturned.compareAndSet(false, true) ? 0xC3 : -1;
        }

        @Override
        public void close() {
            super.close();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseClose.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            cleanupFinished.set(true);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitClose() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            releaseClose.countDown();
        }
    }

    private static final class FailingReadInputStream extends TrackingInputStream {

        private final AssertionError readFailure;
        private final AssertionError closeFailure;

        private FailingReadInputStream(AssertionError readFailure, AssertionError closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            throw readFailure;
        }

        @Override
        public void close() {
            super.close();
            throw closeFailure;
        }
    }

    private static final class ReadErrorInputStream extends TrackingInputStream {

        private final AssertionError failure;

        private ReadErrorInputStream(AssertionError failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            throw failure;
        }
    }

    private static class TrackingOutputStream extends OutputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        final int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class FailingWriteOutputStream extends TrackingOutputStream {

        private final AssertionError failure;
        private final AssertionError closeFailure;

        private FailingWriteOutputStream(AssertionError failure) {
            this(failure, null);
        }

        private FailingWriteOutputStream(AssertionError failure, AssertionError closeFailure) {
            this.failure = failure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void write(int value) {
            throw failure;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            throw failure;
        }

        @Override
        public void close() {
            super.close();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class ImmediateFailingOutputStream extends TrackingOutputStream {

        private final Throwable failure;

        private ImmediateFailingOutputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }
    }

    private static final class NonCooperativeOutputStream extends TrackingOutputStream {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(byte[] bytes, int offset, int length) {
            entered.countDown();
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException interruption) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class TerminalProcess extends Process {

        private final TrackingInputStream stdout;
        private final TrackingInputStream stderr;
        private final TrackingOutputStream stdin;
        private final boolean stayAliveUntilDestroyed;
        private final AtomicBoolean alive;
        private final AtomicInteger stdinGets = new AtomicInteger();
        private final AtomicInteger stdoutGets = new AtomicInteger();
        private final AtomicInteger stderrGets = new AtomicInteger();

        private TerminalProcess(TrackingInputStream stdout, TrackingInputStream stderr, TrackingOutputStream stdin) {
            this(stdout, stderr, stdin, false);
        }

        private TerminalProcess(
                TrackingInputStream stdout,
                TrackingInputStream stderr,
                TrackingOutputStream stdin,
                boolean stayAliveUntilDestroyed) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdin = stdin;
            this.stayAliveUntilDestroyed = stayAliveUntilDestroyed;
            alive = new AtomicBoolean(stayAliveUntilDestroyed);
        }

        @Override
        public OutputStream getOutputStream() {
            stdinGets.incrementAndGet();
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            stdoutGets.incrementAndGet();
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            stderrGets.incrementAndGet();
            return stderr;
        }

        @Override
        public int waitFor() {
            alive.set(false);
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (stdin instanceof NonCooperativeOutputStream nonCooperative) {
                nonCooperative.entered.await(timeout, unit);
            }
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("alive");
            }
            return stayAliveUntilDestroyed ? 143 : 0;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public long pid() {
            return 123L;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class HostileDisplayNameCharset extends Charset {

        private final RuntimeException decoderFailure;
        private final Error displayNameFailure;
        private final AtomicInteger displayNameCalls = new AtomicInteger();

        private HostileDisplayNameCharset(RuntimeException decoderFailure, Error displayNameFailure) {
            super("x-procwright-hostile-display-name", null);
            this.decoderFailure = decoderFailure;
            this.displayNameFailure = displayNameFailure;
        }

        @Override
        public boolean contains(Charset charset) {
            return charset == this;
        }

        @Override
        public String displayName() {
            displayNameCalls.incrementAndGet();
            throw displayNameFailure;
        }

        @Override
        public CharsetDecoder newDecoder() {
            throw decoderFailure;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int displayNameCalls() {
            return displayNameCalls.get();
        }
    }

    private static final class LivenessRestrictedProcess extends Process {

        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return stopped.get();
        }

        @Override
        public int exitValue() {
            if (!stopped.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            stopped.set(true);
        }

        @Override
        public Process destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            stopped.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            if (!stopped.get()) {
                throw new SecurityException("root liveness observation is denied");
            }
            return false;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new SecurityException("descendant enumeration is denied");
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class SelfFailingCleanupProcess extends Process {

        private final AssertionError primaryFailure;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean stdoutClosed = new AtomicBoolean();
        private final AtomicBoolean stderrClosed = new AtomicBoolean();

        private SelfFailingCleanupProcess(AssertionError primaryFailure) {
            this.primaryFailure = primaryFailure;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() {
                    return -1;
                }

                @Override
                public void close() throws IOException {
                    stdoutClosed.set(true);
                    throw primaryFailure;
                }
            };
        }

        @Override
        public InputStream getErrorStream() {
            return new InputStream() {
                @Override
                public int read() {
                    return -1;
                }

                @Override
                public void close() {
                    stderrClosed.set(true);
                }
            };
        }

        @Override
        public int waitFor() {
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return stopped.get();
        }

        @Override
        public int exitValue() {
            if (!stopped.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            throw primaryFailure;
        }

        @Override
        public Process destroyForcibly() {
            stopped.set(true);
            throw primaryFailure;
        }

        @Override
        public boolean isAlive() {
            if (!stopped.get()) {
                throw new SecurityException("root liveness observation is denied");
            }
            return false;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new SecurityException("descendant enumeration is denied");
        }

        private boolean stdoutClosed() {
            return stdoutClosed.get();
        }

        private boolean stderrClosed() {
            return stderrClosed.get();
        }
    }
}
