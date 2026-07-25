/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

final class BoundedCloseDispatcherTestSupport {

    private BoundedCloseDispatcherTestSupport() {}

    static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static void throwCloseFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    static final class CloseSignalInputStream extends InputStream {

        private final CountDownLatch closed;

        CloseSignalInputStream(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
