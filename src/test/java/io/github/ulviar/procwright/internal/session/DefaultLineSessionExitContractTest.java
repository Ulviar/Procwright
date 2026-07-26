/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.openLineSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DefaultLineSessionExitContractTest {

    @Test
    void physicalOutputCloseStartFailureDoesNotDelayPublicExit() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("line stdout close starter failed");
        TrackingCloseInputStream stdout = new TrackingCloseInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, (name, task) -> {
            if (name.contains("stdout-close")) {
                throw startFailure;
            }
            io.github.ulviar.procwright.internal.Threading.start(name, task);
        });
        DefaultLineSession lineSession = openLineSession(process, LineSessionSettings.defaults(), dispatcher);
        try {
            process.complete(0);

            lineSession.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);

            assertEquals(0, stdout.closeCalls.get());
            assertTrue(eventually(() -> dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0));
        } finally {
            process.complete(143);
            lineSession.close();
        }
    }

    private static final class TrackingCloseInputStream extends InputStream {

        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
