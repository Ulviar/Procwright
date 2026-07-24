/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.testcli.TestCli;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
final class PoolTerminalCapacityStressTest {

    private static final int TERMINAL_CAPACITY = 256;
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void publicPoolBoundaryRetainsTerminalCapacityUntilSynchronousContinuationReturns() throws Exception {
        AtomicInteger adapterFactoryCalls = new AtomicInteger();
        CountDownLatch responseReadEntered = new CountDownLatch(1);
        CountDownLatch releaseResponseRead = new CountDownLatch(1);
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        ProtocolSessionScenario.PoolDraft<String, String> draft =
                poolDraft(adapterFactoryCalls, responseReadEntered, releaseResponseRead);
        List<PooledProtocolSession<String, String>> accepted = new ArrayList<>(TERMINAL_CAPACITY + 1);
        List<PooledProtocolSession<String, String>> recoveryWave = new ArrayList<>(TERMINAL_CAPACITY);
        ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> blockingContinuation = null;
        try {
            for (int index = 0; index < TERMINAL_CAPACITY; index++) {
                accepted.add(openEventually(draft));
            }
            assertEquals(0, adapterFactoryCalls.get());

            assertTerminalCapacityFailure(draft, adapterFactoryCalls, 0);

            PooledProtocolSession<String, String> closing = accepted.get(0);
            Future<String> request =
                    requestExecutor.submit(() -> closing.request("terminal-capacity", OPERATION_TIMEOUT));
            assertTrue(responseReadEntered.await(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(1, adapterFactoryCalls.get());

            CompletableFuture<Void> close = closing.closeAsync();
            blockingContinuation = close.thenRun(() -> {
                continuationEntered.countDown();
                awaitUninterruptibly(releaseContinuation);
            });
            releaseResponseRead.countDown();

            assertEquals(
                    "response:terminal-capacity", request.get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(continuationEntered.await(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertFalse(blockingContinuation.isDone());
            assertTerminalCapacityFailure(draft, adapterFactoryCalls, 1);

            releaseContinuation.countDown();
            blockingContinuation.get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            accepted.add(openEventually(draft));
            assertEquals(1, adapterFactoryCalls.get());

            closeAll(accepted);
            for (int index = 0; index < TERMINAL_CAPACITY; index++) {
                recoveryWave.add(openEventually(draft));
            }
            assertEquals(1, adapterFactoryCalls.get());
        } finally {
            releaseResponseRead.countDown();
            releaseContinuation.countDown();
            try {
                if (blockingContinuation != null) {
                    blockingContinuation.get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                }
            } finally {
                try {
                    try {
                        closeAll(accepted);
                    } finally {
                        closeAll(recoveryWave);
                    }
                } finally {
                    requestExecutor.shutdownNow();
                    assertTrue(requestExecutor.awaitTermination(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                }
            }
        }
    }

    private static ProtocolSessionScenario.PoolDraft<String, String> poolDraft(
            AtomicInteger adapterFactoryCalls, CountDownLatch responseReadEntered, CountDownLatch releaseResponseRead) {
        CommandSpec command = CommandSpec.of(javaExecutable())
                .withArgs("-cp", System.getProperty("java.class.path"), TestCli.class.getName());
        return Procwright.command(command)
                .protocolSession(() -> {
                    adapterFactoryCalls.incrementAndGet();
                    return new BlockingLineAdapter(responseReadEntered, releaseResponseRead);
                })
                .withArgs("line-repl")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(0)
                .withCloseTimeout(OPERATION_TIMEOUT);
    }

    private static PooledProtocolSession<String, String> openEventually(
            ProtocolSessionScenario.PoolDraft<String, String> draft) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + OPERATION_TIMEOUT.toNanos();
        while (true) {
            try {
                return draft.open();
            } catch (PooledProtocolSessionException failure) {
                if (failure.reason() != PooledProtocolSessionException.Reason.STARTUP_FAILED
                        || System.nanoTime() >= deadlineNanos) {
                    throw failure;
                }
                Thread.sleep(1);
            }
        }
    }

    private static void assertTerminalCapacityFailure(
            ProtocolSessionScenario.PoolDraft<String, String> draft,
            AtomicInteger adapterFactoryCalls,
            int expectedFactoryCalls) {
        PooledProtocolSessionException failure = assertThrows(PooledProtocolSessionException.class, draft::open);

        assertEquals(PooledProtocolSessionException.Reason.STARTUP_FAILED, failure.reason());
        assertEquals(expectedFactoryCalls, adapterFactoryCalls.get());
    }

    private static void closeAll(List<PooledProtocolSession<String, String>> pools) throws Exception {
        CompletableFuture<?>[] closes =
                pools.stream().map(PooledProtocolSession::closeAsync).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(closes).get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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

    private static String javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static final class BlockingLineAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch responseReadEntered;
        private final CountDownLatch releaseResponseRead;

        private BlockingLineAdapter(CountDownLatch responseReadEntered, CountDownLatch releaseResponseRead) {
            this.responseReadEntered = responseReadEntered;
            this.releaseResponseRead = releaseResponseRead;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            responseReadEntered.countDown();
            awaitUninterruptibly(releaseResponseRead);
            return readers.stdout().readLine(128);
        }
    }
}
