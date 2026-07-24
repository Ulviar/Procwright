/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class PooledLineSessionRequestAndHooksIntegrationSupport extends PooledLineSessionIntegrationSupport {

    static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static final class NonCooperativeTask {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread thread;

        void run() {
            thread = Thread.currentThread();
            entered.countDown();
            awaitIgnoringInterrupt(release);
        }

        boolean awaitEntered() {
            try {
                return entered.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void release() {
            release.countDown();
        }

        void releaseAndJoin() throws InterruptedException {
            release();
            join();
        }

        void join() throws InterruptedException {
            Thread callback = thread;
            if (callback != null) {
                callback.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(callback.isAlive(), "lifecycle callback retained its bounded-runner permit");
            }
        }
    }

    static final class CountingUtf8Charset extends Charset {

        private final AtomicInteger encoderCreations = new AtomicInteger();

        CountingUtf8Charset() {
            super("X-Procwright-Counting-UTF-8", new String[0]);
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

        BlockingUtf8Charset() {
            super("X-Procwright-Pooled-Line-Blocking-UTF-8", new String[0]);
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
                encoderStarted.countDown();
                awaitIgnoringInterrupt(releaseEncoder);
            }
            return StandardCharsets.UTF_8.newEncoder();
        }

        boolean awaitEncoderStarted() throws InterruptedException {
            return encoderStarted.await(1, TimeUnit.SECONDS);
        }

        void releaseEncoder() {
            releaseEncoder.countDown();
        }
    }
}
