/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class SessionConstructionTest {

    @Test
    void rollbackStopsProcessAndAbortsWatchers() throws Exception {
        TrackingProcess process = new TrackingProcess();
        SessionConstruction construction = SessionConstruction.begin(process);
        AtomicBoolean watcherRan = new AtomicBoolean();
        Thread watcher = Threading.start(
                "session-construction-test-", construction.gate().guard(() -> watcherRan.set(true)));

        construction.rollback(new AssertionError("construction failed"));

        watcher.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(watcher.isAlive());
        assertFalse(watcherRan.get());
        assertFalse(process.isAlive());
    }

    @Test
    void rollbackDoesNotAcquireTheConstructionFailureMonitor() throws Exception {
        IllegalStateException primary = new IllegalStateException("construction failed");
        IllegalArgumentException cleanupFailure = new IllegalArgumentException("stdout close failed");
        TrackingProcess process = new CloseFailingProcess(cleanupFailure);
        SessionConstruction construction = SessionConstruction.begin(process);
        construction.own(SessionResources.acquire(process, new BoundedCloseDispatcher(3, 3), () -> {}, ignored -> {}));
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread rollback =
                new Thread(() -> result.set(construction.rollback(primary)), "session-construction-monitor-regression");
        rollback.setDaemon(true);

        try (ThrowableMonitorTestSupport.Hold held = hold(primary)) {
            held.verifyHeld();
            rollback.start();
            rollback.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(rollback.isAlive(), "rollback waited for the caller-owned Throwable monitor");
            assertSame(primary, FailureAggregation.primary(result.get()));
            assertTrue(FailureAggregation.sources(result.get()).contains(cleanupFailure));
        } finally {
            rollback.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertEquals(0, primary.getSuppressed().length);
    }

    private static class TrackingProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);

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
            alive.set(false);
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive.set(false);
            return true;
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 143;
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
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class CloseFailingProcess extends TrackingProcess {

        private final InputStream stdout;

        private CloseFailingProcess(RuntimeException closeFailure) {
            stdout = new InputStream() {
                @Override
                public int read() {
                    return -1;
                }

                @Override
                public void close() {
                    throw closeFailure;
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }
    }
}
