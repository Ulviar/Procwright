/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ProcessTreeScannerStubs.StubProcess;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessTreeScannerOperationOwnershipTest {

    @Test
    void timedOutHostileScanRetainsItsOnlyPermitUntilTheOperationActuallyReturns() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        BlockingDescendantsProcess blocked = new BlockingDescendantsProcess();
        FutureTask<ProcessTreeScanner.DescendantScan> scan =
                new FutureTask<>(() -> scanner.scanDescendants(blocked, Duration.ofMillis(25)));
        Thread worker = new Thread(scan, "process-tree-scan-test");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            ProcessTreeScanner.DescendantScan timedOut = scan.get(1, TimeUnit.SECONDS);
            assertTrue(timedOut.handles().isEmpty());
            assertTrue(timedOut.incomplete());
            assertSame(ProcessTreeScanner.IncompleteReason.CALLER_DEADLINE, timedOut.incompleteReason());
            assertEquals(0, scanner.availableOperationPermits());

            CountingDescendantsProcess rejected = new CountingDescendantsProcess();
            assertTrue(scanner.descendants(rejected).isEmpty());
            assertEquals(0, rejected.calls.get(), "capacity rejection must not invoke another provider process");
        } finally {
            blocked.release.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));

        CountingDescendantsProcess recovered = new CountingDescendantsProcess();
        assertTrue(scanner.descendants(recovered).isEmpty());
        assertEquals(1, recovered.calls.get(), "the released owner must accept later provider work");
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean() && deadline - System.nanoTime() > 0) {
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean restoreInterrupt = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BlockingDescendantsProcess extends StubProcess {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Stream<ProcessHandle> descendants() {
            entered.countDown();
            awaitUninterruptibly(release);
            return Stream.empty();
        }
    }

    private static final class CountingDescendantsProcess extends StubProcess {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Stream<ProcessHandle> descendants() {
            calls.incrementAndGet();
            return Stream.empty();
        }
    }
}
