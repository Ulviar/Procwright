package io.github.ulviar.icli;

import io.github.ulviar.icli.command.CharsetPolicy;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.session.PooledProtocolSessionInvocation;
import io.github.ulviar.icli.session.PooledProtocolSessionOptions;
import io.github.ulviar.icli.session.ProtocolAdapter;
import io.github.ulviar.icli.session.ProtocolSession;
import io.github.ulviar.icli.session.ProtocolSessionInvocation;
import io.github.ulviar.icli.session.ProtocolSessionOptions;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Configures typed protocol workers from an adapter factory.
 *
 * <p>The factory form can open one worker or branch into pooled mode. Each pooled worker receives its own adapter
 * instance.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class ReusableProtocolSessionScenario<I, O> {

    private final CommandService service;
    private final Supplier<? extends ProtocolAdapter<I, O>> adapterFactory;
    private final Consumer<ProtocolSessionInvocation.Builder<I, O>> configure;
    private final Consumer<PooledProtocolSessionInvocation.Builder<I, O>> configurePooled;
    private final ProtocolSessionOptions options;

    ReusableProtocolSessionScenario(
            CommandService service,
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            ProtocolSessionOptions options) {
        this(service, adapterFactory, builder -> {}, builder -> {}, options);
    }

    private ReusableProtocolSessionScenario(
            CommandService service,
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            Consumer<ProtocolSessionInvocation.Builder<I, O>> configure,
            Consumer<PooledProtocolSessionInvocation.Builder<I, O>> configurePooled,
            ProtocolSessionOptions options) {
        this.service = Objects.requireNonNull(service, "service");
        this.adapterFactory = Objects.requireNonNull(adapterFactory, "adapterFactory");
        this.configure = Objects.requireNonNull(configure, "configure");
        this.configurePooled = Objects.requireNonNull(configurePooled, "configurePooled");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Returns a copy with one additional argv argument.
     *
     * @param argument argument value
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withArg(String argument) {
        return withBoth(builder -> builder.arg(argument), builder -> builder.arg(argument));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withArgs(String... arguments) {
        return withBoth(builder -> builder.args(arguments), builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withArgs(Collection<String> arguments) {
        return withBoth(builder -> builder.args(arguments), builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with a per-worker working directory.
     *
     * @param workingDirectory working directory
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withWorkingDirectory(Path workingDirectory) {
        return withBoth(
                builder -> builder.workingDirectory(workingDirectory),
                builder -> builder.workingDirectory(workingDirectory));
    }

    /**
     * Returns a copy with one per-worker environment override.
     *
     * @param name environment variable name
     * @param value environment variable value
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withEnvironment(String name, String value) {
        return withBoth(builder -> builder.putEnvironment(name, value), builder -> builder.putEnvironment(name, value));
    }

    /**
     * Returns a copy that inherits the current process environment before applying overrides.
     *
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withInheritedEnvironment() {
        return withBoth(
                ProtocolSessionInvocation.Builder::inheritEnvironment,
                PooledProtocolSessionInvocation.Builder::inheritEnvironment);
    }

    /**
     * Returns a copy that starts with only configured environment overrides.
     *
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withCleanEnvironment() {
        return withBoth(
                ProtocolSessionInvocation.Builder::cleanEnvironment,
                PooledProtocolSessionInvocation.Builder::cleanEnvironment);
    }

    /**
     * Returns a copy with a per-worker shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withShutdown(ShutdownPolicy shutdownPolicy) {
        return withBoth(builder -> builder.shutdown(shutdownPolicy), builder -> builder.shutdown(shutdownPolicy));
    }

    /**
     * Returns a copy with a caller-visible idle timeout.
     *
     * @param idleTimeout idle timeout, or {@link Duration#ZERO} to disable it
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withIdleTimeout(Duration idleTimeout) {
        return withBoth(builder -> builder.idleTimeout(idleTimeout), builder -> builder.idleTimeout(idleTimeout));
    }

    /**
     * Returns a copy with a terminal policy.
     *
     * @param terminalPolicy terminal policy
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withTerminal(TerminalPolicy terminalPolicy) {
        return withBoth(builder -> builder.terminal(terminalPolicy), builder -> builder.terminal(terminalPolicy));
    }

    /**
     * Returns a copy with a readiness probe.
     *
     * @param readinessProbe readiness probe
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withReadiness(Consumer<ProtocolSession<I, O>> readinessProbe) {
        return withBoth(builder -> builder.readiness(readinessProbe), builder -> builder.readiness(readinessProbe));
    }

    /**
     * Returns a copy with a readiness timeout.
     *
     * @param readinessTimeout readiness timeout
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withReadinessTimeout(Duration readinessTimeout) {
        return withBoth(
                builder -> builder.readinessTimeout(readinessTimeout),
                builder -> builder.readinessTimeout(readinessTimeout));
    }

    /**
     * Returns a copy with a default request timeout.
     *
     * @param requestTimeout request timeout
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withRequestTimeout(Duration requestTimeout) {
        return withOptions(options.withRequestTimeout(requestTimeout));
    }

    /**
     * Returns a copy with a retained transcript limit.
     *
     * @param transcriptLimit transcript limit
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withTranscriptLimit(int transcriptLimit) {
        return withOptions(options.withTranscriptLimit(transcriptLimit));
    }

    /**
     * Returns a copy with a pending output byte limit per stream.
     *
     * @param outputBacklogLimit output backlog limit
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withOutputBacklogLimit(int outputBacklogLimit) {
        return withOptions(options.withOutputBacklogLimit(outputBacklogLimit));
    }

    /**
     * Returns a copy with a request byte limit.
     *
     * @param maxRequestBytes request byte limit
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withMaxRequestBytes(int maxRequestBytes) {
        return withOptions(options.withMaxRequestBytes(maxRequestBytes));
    }

    /**
     * Returns a copy with a request character limit for text writes.
     *
     * @param maxRequestChars request character limit
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withMaxRequestChars(int maxRequestChars) {
        return withOptions(options.withMaxRequestChars(maxRequestChars));
    }

    /**
     * Returns a copy with a response byte limit.
     *
     * @param maxResponseBytes response byte limit
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withMaxResponseBytes(int maxResponseBytes) {
        return withOptions(options.withMaxResponseBytes(maxResponseBytes));
    }

    /**
     * Returns a copy with a response character limit for text reads.
     *
     * @param maxResponseChars response character limit
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withMaxResponseChars(int maxResponseChars) {
        return withOptions(options.withMaxResponseChars(maxResponseChars));
    }

    /**
     * Returns a copy with a protocol text charset policy.
     *
     * @param charsetPolicy charset policy
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withCharsetPolicy(CharsetPolicy charsetPolicy) {
        return withOptions(options.withCharsetPolicy(charsetPolicy));
    }

    /**
     * Returns a copy with explicit protocol-session options.
     *
     * @param options protocol-session options
     * @return updated scenario
     */
    public ReusableProtocolSessionScenario<I, O> withOptions(ProtocolSessionOptions options) {
        return new ReusableProtocolSessionScenario<>(service, adapterFactory, configure, configurePooled, options);
    }

    /**
     * Branches into pooled protocol-session mode.
     *
     * @return pooled protocol-session scenario
     */
    public PooledProtocolSessionScenario<I, O> pooled() {
        PooledProtocolSessionOptions poolOptions = service.pooledProtocolSessionOptions();
        return new PooledProtocolSessionScenario<>(service, adapterFactory, configurePooled, options, poolOptions);
    }

    /**
     * Opens one configured protocol session using a fresh adapter instance.
     *
     * @return protocol session
     */
    public ProtocolSession<I, O> open() {
        ProtocolSessionInvocation.Builder<I, O> builder = ProtocolSessionInvocation.builder(options);
        configure.accept(builder);
        return service.openProtocolSession(service.createProtocolAdapter(adapterFactory), builder.build());
    }

    private ReusableProtocolSessionScenario<I, O> withBoth(
            Consumer<ProtocolSessionInvocation.Builder<I, O>> protocolStep,
            Consumer<PooledProtocolSessionInvocation.Builder<I, O>> pooledStep) {
        Objects.requireNonNull(protocolStep, "protocolStep");
        Objects.requireNonNull(pooledStep, "pooledStep");
        return new ReusableProtocolSessionScenario<>(
                service, adapterFactory, configure.andThen(protocolStep), configurePooled.andThen(pooledStep), options);
    }
}
