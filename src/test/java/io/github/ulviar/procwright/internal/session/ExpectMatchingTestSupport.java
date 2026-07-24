/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

abstract class ExpectMatchingTestSupport extends ExpectTestSupport {
    static String randomUnicodeText(Random random, int codePoints) {
        String[] alphabet = {"a", "b", "\u00E9", "\uD83D\uDE03"};
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < codePoints; index++) {
            text.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return text.toString();
    }

    static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    static DefaultExpect expect(
            ControllableProcess process,
            BoundedTaskLimiter limiter,
            ExpectRegexMatcher.Evaluator evaluator,
            ExpectSettings settings,
            PumpStarter pumpStarter) {
        return new DefaultExpect(
                session(process), settings, ZeroReadBackoff.exponential(), pumpStarter, limiter, evaluator);
    }

    static ExpectException expectFailure(Future<ExpectMatch> future) throws Exception {
        ExecutionException wrapper = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        return assertInstanceOf(ExpectException.class, wrapper.getCause());
    }

    static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    static final class BlockingFirstRegexEvaluator implements ExpectRegexMatcher.Evaluator {

        final AtomicInteger invocations = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);

        @Override
        public ExpectRegexMatcher.Evaluation find(Pattern pattern, String text, int searchStart) {
            if (invocations.getAndIncrement() == 0) {
                started.countDown();
                try {
                    awaitUninterruptibly(release);
                } finally {
                    stopped.countDown();
                }
            }
            return ExpectRegexMatcher.evaluate(pattern, text, searchStart);
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        boolean awaitStopped() throws InterruptedException {
            return stopped.await(1, TimeUnit.SECONDS);
        }

        void awaitInvocationStopped() throws InterruptedException {
            assertTrue(stopped.await(1, TimeUnit.SECONDS), "controlled matcher invocation must terminate");
        }
    }

    static final class BlockingErrorRegexEvaluator implements ExpectRegexMatcher.Evaluator {

        final Error failure;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();

        BlockingErrorRegexEvaluator(Error failure) {
            this.failure = failure;
        }

        @Override
        public ExpectRegexMatcher.Evaluation find(Pattern pattern, String text, int searchStart) {
            worker.set(Thread.currentThread());
            started.countDown();
            try {
                awaitUninterruptibly(release);
                throw failure;
            } finally {
                stopped.countDown();
            }
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        Thread worker() {
            return worker.get();
        }

        void awaitInvocationStopped() throws InterruptedException {
            assertTrue(stopped.await(1, TimeUnit.SECONDS), "controlled matcher invocation must terminate");
        }
    }

    static final class GatedFailureInputStream extends InputStream {

        final RuntimeException failure;
        final CountDownLatch failed = new CountDownLatch(1);

        GatedFailureInputStream(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            awaitUninterruptibly(failed);
            throw failure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            failed.countDown();
        }

        void fail() {
            failed.countDown();
        }
    }

    static final class PumpCompletionTracker implements PumpStarter {

        final CountDownLatch stdoutStopped = new CountDownLatch(1);

        @Override
        public Thread start(String namePrefix, Runnable task) {
            return Threading.start(namePrefix, () -> {
                try {
                    task.run();
                } finally {
                    if (namePrefix.contains("stdout")) {
                        stdoutStopped.countDown();
                    }
                }
            });
        }

        boolean awaitStdoutStopped() throws InterruptedException {
            return stdoutStopped.await(1, TimeUnit.SECONDS);
        }
    }
}
