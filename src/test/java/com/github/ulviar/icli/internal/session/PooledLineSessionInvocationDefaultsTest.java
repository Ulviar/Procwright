package com.github.ulviar.icli.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.ulviar.icli.session.PooledLineSessionInvocation;
import com.github.ulviar.icli.session.PooledLineSessionOptions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

final class PooledLineSessionInvocationDefaultsTest {

    @Test
    void appliesAllServiceLevelPoolDefaultsBeforeUserOverrides() {
        Consumer<com.github.ulviar.icli.session.LineSession> reset = worker -> {};
        Predicate<com.github.ulviar.icli.session.LineSession> healthCheck = worker -> true;
        PooledLineSessionOptions defaults = new PooledLineSessionOptions(
                4, 2, 1, Duration.ofMillis(250), 7, Duration.ofMinutes(3), false, reset, healthCheck);

        PooledLineSessionInvocation invocation = PooledLineSessionInvocationDefaults.builder(defaults)
                .args("line-repl")
                .maxSize(3)
                .build();

        assertEquals(
                java.util.List.of("line-repl"),
                invocation.lineSessionInvocation().arguments());
        assertEquals(3, invocation.options().maxSize());
        assertEquals(2, invocation.options().warmupSize());
        assertEquals(1, invocation.options().minIdle());
        assertEquals(Duration.ofMillis(250), invocation.options().acquireTimeout());
        assertEquals(7, invocation.options().maxRequestsPerWorker());
        assertEquals(Duration.ofMinutes(3), invocation.options().maxWorkerAge());
        assertEquals(false, invocation.options().backgroundReplenishment());
        assertSame(reset, invocation.options().resetHook());
        assertSame(healthCheck, invocation.options().healthCheck());
    }

    @Test
    void keepsCallbacksUsableAfterDefaultApplication() {
        AtomicInteger resetCalls = new AtomicInteger();
        PooledLineSessionOptions defaults =
                PooledLineSessionOptions.defaults().withReset(worker -> resetCalls.incrementAndGet());

        PooledLineSessionInvocation invocation =
                PooledLineSessionInvocationDefaults.builder(defaults).build();

        invocation.options().resetHook().accept(null);

        assertEquals(1, resetCalls.get());
    }
}
