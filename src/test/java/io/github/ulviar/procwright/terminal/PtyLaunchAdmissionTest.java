/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

final class PtyLaunchAdmissionTest {

    private static final SystemPtyProvider.SystemPtySupport SUPPORT = new SystemPtyProvider.SystemPtySupport(
            SystemPtyProvider.ScriptFlavor.BSD,
            Path.of("/usr/bin/script"),
            Path.of("/bin/sh"),
            Path.of("/usr/bin/stty"),
            Path.of("/usr/bin/env"),
            Path.of("/bin/dd"),
            "admission test provider");

    @Test
    void timedOutPreparersKeepAdmissionUntilTheirTasksActuallyComplete() throws Exception {
        int limit = PtyLaunchAdmission.MAX_CONCURRENT_TASKS;
        CountDownLatch entered = new CountDownLatch(limit);
        CountDownLatch release = new CountDownLatch(1);
        SystemPtyProvider provider = new SystemPtyProvider(
                SUPPORT,
                payload -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    return PtyBootstrap.prepare(payload);
                },
                builder -> PtyTestProcess.completed(0),
                Duration.ofMillis(500));
        ExecutorService callers = Executors.newFixedThreadPool(limit);
        try {
            List<Future<?>> launches = launchConcurrently(callers, provider, limit);
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            CommandExecutionException overflow =
                    assertThrows(CommandExecutionException.class, () -> provider.start(request()));

            assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, overflow.reason());
            assertTrue(String.valueOf(overflow.getCause().getMessage()).contains("capacity"));
            for (Future<?> launch : launches) {
                launch.get(2, TimeUnit.SECONDS);
            }
            assertThrows(CommandExecutionException.class, () -> provider.start(request()));

            release.countDown();
            Process admitted = awaitSuccessfulLaunch();
            admitted.destroyForcibly();
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void lateStartedProcessesAreClosedAndDoNotReleaseCapacityEarly() throws Exception {
        int limit = PtyLaunchAdmission.MAX_CONCURRENT_TASKS;
        CountDownLatch entered = new CountDownLatch(limit);
        CountDownLatch release = new CountDownLatch(1);
        List<PtyTestProcess> lateProcesses = new CopyOnWriteArrayList<>();
        SystemPtyProvider provider = new SystemPtyProvider(
                SUPPORT,
                PtyBootstrap::prepare,
                builder -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    PtyTestProcess process = PtyTestProcess.completed(0);
                    lateProcesses.add(process);
                    return process;
                },
                Duration.ofMillis(500));
        ExecutorService callers = Executors.newFixedThreadPool(limit);
        try {
            List<Future<?>> launches = launchConcurrently(callers, provider, limit);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (Future<?> launch : launches) {
                launch.get(2, TimeUnit.SECONDS);
            }

            assertThrows(CommandExecutionException.class, () -> provider.start(request()));
            release.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((lateProcesses.size() != limit
                            || lateProcesses.stream().anyMatch(process -> !process.streamsClosed()))
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(limit, lateProcesses.size());
            assertTrue(lateProcesses.stream().allMatch(PtyTestProcess::destroyed));
            assertTrue(lateProcesses.stream().allMatch(PtyTestProcess::streamsClosed));

            Process admitted = awaitSuccessfulLaunch();
            admitted.destroyForcibly();
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static List<Future<?>> launchConcurrently(ExecutorService callers, SystemPtyProvider provider, int count) {
        ArrayList<Future<?>> launches = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            launches.add(callers.submit(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (true) {
                    CommandExecutionException failure =
                            assertThrows(CommandExecutionException.class, () -> provider.start(request()));
                    if (!isCapacityFailure(failure) || System.nanoTime() >= deadline) {
                        return;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
            }));
        }
        return List.copyOf(launches);
    }

    private static boolean isCapacityFailure(CommandExecutionException failure) {
        return failure.getCause() != null
                && String.valueOf(failure.getCause().getMessage()).contains("capacity");
    }

    private static Process awaitSuccessfulLaunch() throws Exception {
        SystemPtyProvider provider = new SystemPtyProvider(
                SUPPORT, PtyBootstrap::prepare, builder -> PtyTestProcess.completed(0), Duration.ofSeconds(1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        CommandExecutionException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return provider.start(request());
            } catch (CommandExecutionException failure) {
                lastFailure = failure;
                Thread.sleep(5);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("admission did not recover");
    }

    private static PtyRequest request() {
        return new PtyRequest(
                List.of("/usr/bin/true"),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                Map.of(),
                new TerminalSize(80, 24));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
