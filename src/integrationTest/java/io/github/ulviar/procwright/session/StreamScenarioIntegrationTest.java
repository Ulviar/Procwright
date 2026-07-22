/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.StreamScenario;
import io.github.ulviar.procwright.TestCliSupport;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class StreamScenarioIntegrationTest {

    @Test
    void listenDispatchesStdoutAndStderrChunksAndReportsExit() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        StreamScenario.Draft scenario = fixtureDraft();

        try (StreamSession session = scenario.withArgs("exit", "--stdout=out", "--stderr=err")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertFalse(exit.timedOut());
            assertTrue(chunks.stream()
                    .anyMatch(chunk -> chunk.source() == StreamSource.STDOUT
                            && chunk.text().contains("out")));
            assertTrue(chunks.stream()
                    .anyMatch(chunk -> chunk.source() == StreamSource.STDERR
                            && chunk.text().contains("err")));
            assertTrue(exit.diagnostics().text().contains("stdout: out"));
            assertTrue(exit.diagnostics().text().contains("stderr: err"));
        }
    }

    @Test
    void listenClosesStdinOnStart() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        StreamScenario.Draft scenario = fixtureDraft();

        try (StreamSession session = scenario.withArgs("stdin-echo", "--mode=bytes-count")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("bytes:0")));
        }
    }

    @Test
    void streamTimeoutStopsLongRunningProcess() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        StreamScenario.Draft scenario = fixtureDraft().withTimeout(timeoutAfterFixtureStartup());

        try (StreamSession session = scenario.withArgs("sleep", "--millis=5000", "--finished=false")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(exitWaitTimeout().toSeconds(), TimeUnit.SECONDS);

            assertTrue(exit.timedOut());
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("started")));
        }
    }

    @Test
    void explicitCloseReportsClosedExit() throws Exception {
        StreamScenario.Draft scenario = fixtureDraft();

        StreamSession session =
                scenario.withArgs("sleep", "--millis=5000", "--finished=false").open();
        try {
            session.close();

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);
            assertTrue(exit.closed());
        } finally {
            session.close();
        }
    }

    @Test
    void listenerFailureCompletesExitExceptionallyWithDiagnostics() {
        StreamScenario.Draft scenario = fixtureDraft();

        try (StreamSession session = scenario.withArgs("exit", "--stdout=boom")
                .onOutput(chunk -> {
                    throw new IllegalStateException("listener failed");
                })
                .open()) {
            ExecutionException exception = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamException = assertInstanceOf(StreamException.class, exception.getCause());

            assertEquals(StreamException.Reason.LISTENER_FAILED, streamException.reason());
            assertTrue(streamException.diagnostics().text().contains("stdout: boom"));
        }
    }

    @Test
    void listenerErrorCompletesExitExceptionallyAndStopsProcess() throws Exception {
        StreamScenario.Draft scenario = fixtureDraft();
        AssertionError listenerFailure = new AssertionError("listener error");
        CountDownLatch listenerInvoked = new CountDownLatch(1);
        AtomicLong childPid = new AtomicLong(-1);
        StringBuilder fixtureOutput = new StringBuilder();

        try (StreamSession session = scenario.withArgs("spawn-child", "--child-scenario=never-exit", "--wait=true")
                .onOutput(chunk -> {
                    if (chunk.source() != StreamSource.STDOUT) {
                        return;
                    }
                    fixtureOutput.append(chunk.text());
                    long parsedPid = completeChildPid(fixtureOutput);
                    if (parsedPid > 0 && childPid.compareAndSet(-1, parsedPid)) {
                        listenerInvoked.countDown();
                        throw listenerFailure;
                    }
                })
                .open()) {
            assertTrue(
                    listenerInvoked.await(2, TimeUnit.SECONDS), "the never-exiting fixture did not reach the listener");
            ExecutionException exception = assertThrows(ExecutionException.class, () -> session.onExit()
                    .get(exitWaitTimeout().toSeconds(), TimeUnit.SECONDS));

            assertSame(listenerFailure, exception.getCause());
            assertTrue(
                    waitForProcessExit(childPid.get(), exitWaitTimeout()),
                    "listener failure completion left the never-exiting child alive");
        } finally {
            forceKillAndWait(childPid.get());
        }
    }

    @Test
    void concurrentOutputIsAggregatedBySourceWithoutAssumingChunkBoundaries() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        StreamScenario.Draft scenario = fixtureDraft();

        try (StreamSession session = scenario.withArgs("concurrent-output", "--stdout=out-ready", "--stderr=err-ready")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(exitWaitTimeout().toSeconds(), TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertEquals("out-ready\n", aggregate(chunks, StreamSource.STDOUT));
            assertEquals("err-ready\n", aggregate(chunks, StreamSource.STDERR));
        }
    }

    @Test
    void callerCannotCompleteStreamExitFuture() throws Exception {
        StreamScenario.Draft scenario = fixtureDraft();

        try (StreamSession session = scenario.withArgs("sleep", "--millis=200").open()) {
            CompletableFuture<StreamExit> callerFuture = session.onExit();
            callerFuture.complete(new StreamExit(
                    java.util.OptionalInt.of(99), false, false, new StreamTranscript("fake", false), Duration.ZERO));

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);
            assertEquals(0, exit.exitCode().orElseThrow());
        }
    }

    @Test
    void boundedDiagnosticsDoNotStoreEntireOutput() throws Exception {
        StreamScenario.Draft scenario = fixtureDraft().withDiagnosticLimit(64);

        try (StreamSession session = scenario.withArgs("burst", "--stdout-bytes=128", "--stdout-byte=x")
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.diagnostics().truncated());
            assertTrue(exit.diagnostics().text().length() <= 64);
        }
    }

    @Test
    void stderrIsDrainedWhileStreamingStdout() throws Exception {
        CopyOnWriteArrayList<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        StreamScenario.Draft scenario = fixtureDraft().withDiagnosticLimit(1024);

        try (StreamSession session = scenario.withArgs(
                        "burst",
                        "--stdout-first=false",
                        "--stdout-bytes=5",
                        "--stdout-byte=d",
                        "--stderr-bytes=256k",
                        "--stderr-byte=e")
                .onOutput(chunks::add)
                .open()) {
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertTrue(chunks.stream()
                    .anyMatch(chunk -> chunk.source() == StreamSource.STDOUT
                            && chunk.text().contains("ddddd")));
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.source() == StreamSource.STDERR));
        }
    }

    private static StreamScenario.Draft fixtureDraft() {
        return Procwright.command(TestCliSupport.command()).listen();
    }

    private static String aggregate(Iterable<StreamChunk> chunks, StreamSource source) {
        StringBuilder output = new StringBuilder();
        for (StreamChunk chunk : chunks) {
            if (chunk.source() == source) {
                output.append(chunk.text());
            }
        }
        return output.toString();
    }

    private static long completeChildPid(CharSequence output) {
        String text = output.toString();
        int marker = text.indexOf("child:");
        if (marker < 0) {
            return -1;
        }
        int valueStart = marker + "child:".length();
        int lineEnd = text.indexOf('\n', valueStart);
        if (lineEnd < 0) {
            return -1;
        }
        long pid = Long.parseLong(text.substring(valueStart, lineEnd).trim());
        if (pid <= 0) {
            throw new AssertionError("fixture reported a non-positive child pid: " + pid);
        }
        return pid;
    }

    private static boolean waitForProcessExit(long pid, Duration timeout) throws InterruptedException {
        if (pid <= 0) {
            return false;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(10);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    private static void forceKillAndWait(long pid) {
        if (pid <= 0) {
            return;
        }
        try {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            waitForProcessExit(pid, Duration.ofSeconds(2));
        } catch (RuntimeException ignored) {
            // Test cleanup must not replace the listener assertion.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static Duration exitWaitTimeout() {
        return isWindows() ? Duration.ofSeconds(6) : Duration.ofSeconds(2);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
