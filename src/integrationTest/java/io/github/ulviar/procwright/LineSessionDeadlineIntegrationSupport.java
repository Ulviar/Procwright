/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineSession;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class LineSessionDeadlineIntegrationSupport extends LineSessionIntegrationSupport {

    static boolean eventuallyTranscriptContains(LineSession session, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (session.transcript().text().contains(expected)) {
                return true;
            }
            Thread.sleep(10);
        }
        return session.transcript().text().contains(expected);
    }

    static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static void assertTaskStopped(Thread thread, String task) throws InterruptedException {
        assertTrue(thread != null, task + " thread was not captured");
        thread.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(thread.isAlive(), task + " thread retained its bounded-runner permit");
    }

    static final class CountingUtf8Charset extends Charset {

        private final Duration firstEncoderDelay;
        private final AtomicBoolean delayPending = new AtomicBoolean(true);
        private final AtomicInteger encoderCreations = new AtomicInteger();

        CountingUtf8Charset(Duration firstEncoderDelay) {
            super("X-Procwright-Line-Counting-UTF-8", new String[0]);
            this.firstEncoderDelay = firstEncoderDelay;
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations.incrementAndGet();
            if (delayPending.compareAndSet(true, false) && !firstEncoderDelay.isZero()) {
                sleep(firstEncoderDelay);
            }
            return StandardCharsets.UTF_8.newEncoder();
        }

        int encoderCreations() {
            return encoderCreations.get();
        }
    }

    static final class BlockingUtf8Charset extends Charset {

        private final AtomicBoolean blockNextEncoder = new AtomicBoolean(true);
        private final CountDownLatch encoderStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEncoder = new CountDownLatch(1);
        private final CountDownLatch encoderFinished = new CountDownLatch(1);
        private volatile Thread encoderThread;

        BlockingUtf8Charset() {
            super("X-Procwright-Line-Blocking-UTF-8", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            if (blockNextEncoder.compareAndSet(true, false)) {
                encoderThread = Thread.currentThread();
                encoderStarted.countDown();
                try {
                    awaitIgnoringInterrupts(releaseEncoder);
                } finally {
                    encoderFinished.countDown();
                }
            }
            return StandardCharsets.UTF_8.newEncoder();
        }

        boolean awaitEncoderStarted() throws InterruptedException {
            return encoderStarted.await(1, TimeUnit.SECONDS);
        }

        void releaseEncoder() {
            releaseEncoder.countDown();
        }

        boolean awaitEncoderFinished() throws InterruptedException {
            return encoderFinished.await(1, TimeUnit.SECONDS);
        }

        boolean awaitEncoderTaskStopped() throws InterruptedException {
            Thread task = encoderThread;
            if (task == null) {
                return false;
            }
            task.join(TimeUnit.SECONDS.toMillis(1));
            return !task.isAlive();
        }
    }
}
