/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

abstract class OutputPumpCleanupTestSupport extends OutputPumpTestSupport {
    static final class GatedThrowingCloseInputStream extends InputStream {

        final Error closeFailure;
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeRelease = new CountDownLatch(1);
        final CountDownLatch closeCompleted = new CountDownLatch(1);

        GatedThrowingCloseInputStream(Error closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
            throw closeFailure;
        }

        boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        void releaseClose() {
            closeRelease.countDown();
        }
    }
}
