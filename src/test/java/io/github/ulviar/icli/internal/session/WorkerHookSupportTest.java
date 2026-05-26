package io.github.ulviar.icli.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class WorkerHookSupportTest {

    @Test
    void returnsSuccessfulHookValue() {
        String value = WorkerHookSupport.run(
                "icli-test-hook-",
                Duration.ofSeconds(1),
                () -> "ok",
                () -> new IllegalStateException("timeout"),
                interrupted -> new IllegalStateException("interrupted", interrupted),
                failure -> new IllegalStateException("failure", failure));

        assertEquals("ok", value);
    }

    @Test
    void mapsTimeoutAndInterruptsWorker() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> WorkerHookSupport.run(
                        "icli-test-hook-",
                        Duration.ofMillis(20),
                        () -> {
                            try {
                                Thread.sleep(10_000);
                            } catch (InterruptedException caught) {
                                interrupted.set(true);
                                Thread.currentThread().interrupt();
                            }
                            return "late";
                        },
                        () -> new IllegalStateException("timeout"),
                        caught -> new IllegalStateException("interrupted", caught),
                        failure -> new IllegalStateException("failure", failure)));

        assertEquals("timeout", exception.getMessage());
        assertTrue(eventuallyTrue(interrupted));
    }

    @Test
    void mapsCallerInterruptionSeparatelyFromTimeout() {
        try {
            Thread.currentThread().interrupt();

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> WorkerHookSupport.run(
                            "icli-test-hook-",
                            Duration.ofSeconds(5),
                            () -> {
                                try {
                                    Thread.sleep(10_000);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                return null;
                            },
                            () -> new IllegalStateException("timeout"),
                            interrupted -> new IllegalStateException("interrupted", interrupted),
                            failure -> new IllegalStateException("failure", failure)));

            assertInstanceOf(InterruptedException.class, exception.getCause());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void mapsRuntimeFailure() {
        IllegalArgumentException cause = new IllegalArgumentException("bad hook");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> WorkerHookSupport.run(
                        "icli-test-hook-",
                        Duration.ofSeconds(1),
                        () -> {
                            throw cause;
                        },
                        () -> new IllegalStateException("timeout"),
                        interrupted -> new IllegalStateException("interrupted", interrupted),
                        failure -> new IllegalStateException("failure", failure)));

        assertSame(cause, exception.getCause());
    }

    @Test
    void rethrowsSeriousErrors() {
        AssertionError error = new AssertionError("serious");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> WorkerHookSupport.run(
                        "icli-test-hook-",
                        Duration.ofSeconds(1),
                        () -> {
                            throw error;
                        },
                        () -> new IllegalStateException("timeout"),
                        interrupted -> new IllegalStateException("interrupted", interrupted),
                        failure -> new IllegalStateException("failure", failure)));

        assertSame(error, thrown);
    }

    @Test
    void boundedTimeoutReturnsZeroAfterDeadline() {
        Duration bounded = WorkerHookSupport.boundedTimeout(Duration.ofSeconds(1), System.nanoTime() - 1);

        assertEquals(Duration.ZERO, bounded);
    }

    @Test
    void boundedTimeoutDoesNotExceedHookTimeoutOrRemainingDeadline() {
        Duration hookTimeout = Duration.ofSeconds(5);
        Duration remaining = Duration.ofMillis(200);

        Duration bounded = WorkerHookSupport.boundedTimeout(hookTimeout, System.nanoTime() + remaining.toNanos());

        assertTrue(bounded.compareTo(Duration.ZERO) > 0);
        assertTrue(bounded.compareTo(hookTimeout) <= 0);
        assertTrue(bounded.compareTo(remaining) <= 0);
    }

    private static boolean eventuallyTrue(AtomicBoolean value) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (value.get()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}
