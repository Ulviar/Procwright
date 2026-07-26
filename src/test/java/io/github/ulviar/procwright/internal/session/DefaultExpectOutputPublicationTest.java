/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.openExpect;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.passthroughDecoder;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.Threading;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DefaultExpectOutputPublicationTest {

    @Test
    void closeWinningBeforeDecodedPublicationPreventsPublication() throws Exception {
        GatedPublicationCharset charset = new GatedPublicationCharset();
        TrackingPumpStarter pumpStarter = new TrackingPumpStarter();
        ControllableProcess process =
                new ControllableProcess(new ByteArrayInputStream(new byte[] {'x'}), InputStream.nullInputStream());
        DefaultExpect expect = openExpect(
                process,
                charset,
                session -> new DefaultExpect(
                        session, ExpectSettings.defaults(), ZeroReadBackoff.exponential(), pumpStarter));
        try {
            assertTrue(charset.awaitPublicationReady());

            expect.close();
            charset.releasePublication();

            assertTrue(pumpStarter.awaitStopped());
            assertFalse(expect.transcript().text().contains("x"));
        } finally {
            charset.releasePublication();
            expect.close();
        }
    }

    private static final class GatedPublicationCharset extends Charset {

        private final CountDownLatch publicationReady = new CountDownLatch(1);
        private final CountDownLatch publicationRelease = new CountDownLatch(1);

        private GatedPublicationCharset() {
            super("X-Procwright-Expect-Gated-Publication", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (!Thread.currentThread().getName().contains("stdout")) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    publicationReady.countDown();
                    awaitUninterruptibly(publicationRelease);
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private boolean awaitPublicationReady() throws InterruptedException {
            return publicationReady.await(1, TimeUnit.SECONDS);
        }

        private void releasePublication() {
            publicationRelease.countDown();
        }
    }

    private static final class TrackingPumpStarter implements PumpStarter {

        private final List<Thread> threads = new ArrayList<>();

        @Override
        public Thread start(String namePrefix, Runnable task) {
            Thread thread = Threading.start(namePrefix, task);
            threads.add(thread);
            return thread;
        }

        private boolean awaitStopped() throws InterruptedException {
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
        }
    }
}
