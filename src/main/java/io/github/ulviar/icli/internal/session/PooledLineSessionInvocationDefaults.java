package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.session.PooledLineSessionInvocation;
import io.github.ulviar.icli.session.PooledLineSessionOptions;
import java.util.Objects;

public final class PooledLineSessionInvocationDefaults {

    private PooledLineSessionInvocationDefaults() {}

    public static PooledLineSessionInvocation.Builder builder(PooledLineSessionOptions defaults) {
        PooledLineSessionOptions options = Objects.requireNonNull(defaults, "defaults");
        return PooledLineSessionInvocation.builder()
                .maxSize(options.maxSize())
                .warmupSize(options.warmupSize())
                .minIdle(options.minIdle())
                .acquireTimeout(options.acquireTimeout())
                .maxRequestsPerWorker(options.maxRequestsPerWorker())
                .maxWorkerAge(options.maxWorkerAge())
                .backgroundReplenishment(options.backgroundReplenishment())
                .reset(options.resetHook())
                .healthCheck(options.healthCheck());
    }
}
