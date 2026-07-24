/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.Threading;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class SessionConstructionTest {

    @Test
    void rollbackReleasesPublicationOwnersStopsProcessAndAbortsWatchers() throws Exception {
        TrackingProcess process = new TrackingProcess();
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(1);
        BoundedLifecyclePublisher.Permit permit = reservation.takePermit();
        SessionConstruction construction = SessionConstruction.begin(process);
        construction.own(reservation);
        construction.own(permit);
        AtomicBoolean watcherRan = new AtomicBoolean();
        Thread watcher = Threading.start(
                "session-construction-test-", construction.gate().guard(() -> watcherRan.set(true)));

        construction.rollback(new AssertionError("construction failed"));

        watcher.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(watcher.isAlive());
        assertFalse(watcherRan.get());
        assertFalse(process.isAlive());
        assertEquals(0, publisher.ownerCount());
    }

    private static final class TrackingProcess extends Process {

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
}
