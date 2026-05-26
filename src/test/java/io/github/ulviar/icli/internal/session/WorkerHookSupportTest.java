package io.github.ulviar.icli.internal.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class WorkerHookSupportTest {

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
}
