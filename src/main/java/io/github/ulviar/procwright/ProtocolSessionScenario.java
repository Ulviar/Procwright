package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionInvocation;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Configures and opens one typed request/response protocol worker.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class ProtocolSessionScenario<I, O> {

    private final CommandService service;
    private final ProtocolAdapter<I, O> adapter;
    private final Consumer<ProtocolSessionInvocation.Builder<I, O>> configure;
    private final ProtocolSessionOptions options;

    ProtocolSessionScenario(CommandService service, ProtocolAdapter<I, O> adapter, ProtocolSessionOptions options) {
        this(service, adapter, builder -> {}, options);
    }

    private ProtocolSessionScenario(
            CommandService service,
            ProtocolAdapter<I, O> adapter,
            Consumer<ProtocolSessionInvocation.Builder<I, O>> configure,
            ProtocolSessionOptions options) {
        this.service = Objects.requireNonNull(service, "service");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.configure = Objects.requireNonNull(configure, "configure");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Returns a copy with one additional argv argument.
     *
     * @param argument argument value
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withArg(String argument) {
        return with(builder -> builder.arg(argument));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withArgs(String... arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withArgs(Collection<String> arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with a per-worker working directory.
     *
     * @param workingDirectory working directory
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withWorkingDirectory(Path workingDirectory) {
        return with(builder -> builder.workingDirectory(workingDirectory));
    }

    /**
     * Returns a copy with one per-worker environment override.
     *
     * @param name environment variable name
     * @param value environment variable value
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withEnvironment(String name, String value) {
        return with(builder -> builder.putEnvironment(name, value));
    }

    /**
     * Returns a copy that inherits the current process environment before applying overrides.
     *
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withInheritedEnvironment() {
        return with(ProtocolSessionInvocation.Builder::inheritEnvironment);
    }

    /**
     * Returns a copy that starts with only configured environment overrides.
     *
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withCleanEnvironment() {
        return with(ProtocolSessionInvocation.Builder::cleanEnvironment);
    }

    /**
     * Returns a copy with a per-worker shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withShutdown(ShutdownPolicy shutdownPolicy) {
        return with(builder -> builder.shutdown(shutdownPolicy));
    }

    /**
     * Returns a copy with a caller-visible idle timeout.
     *
     * @param idleTimeout idle timeout, or {@link Duration#ZERO} to disable it
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withIdleTimeout(Duration idleTimeout) {
        return with(builder -> builder.idleTimeout(idleTimeout));
    }

    /**
     * Returns a copy with a terminal policy.
     *
     * @param terminalPolicy terminal policy
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withTerminal(TerminalPolicy terminalPolicy) {
        return with(builder -> builder.terminal(terminalPolicy));
    }

    /**
     * Returns a copy with a readiness probe.
     *
     * @param readinessProbe readiness probe
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withReadiness(Consumer<ProtocolSession<I, O>> readinessProbe) {
        return with(builder -> builder.readiness(readinessProbe));
    }

    /**
     * Returns a copy with a readiness timeout.
     *
     * @param readinessTimeout readiness timeout
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withReadinessTimeout(Duration readinessTimeout) {
        return with(builder -> builder.readinessTimeout(readinessTimeout));
    }

    /**
     * Returns a copy with a default request timeout.
     *
     * @param requestTimeout request timeout
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withRequestTimeout(Duration requestTimeout) {
        return withOptions(options.withRequestTimeout(requestTimeout));
    }

    /**
     * Returns a copy with a retained transcript limit.
     *
     * @param transcriptLimit transcript limit
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withTranscriptLimit(int transcriptLimit) {
        return withOptions(options.withTranscriptLimit(transcriptLimit));
    }

    /**
     * Returns a copy with a pending output byte limit per stream.
     *
     * @param outputBacklogLimit output backlog limit
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withOutputBacklogLimit(int outputBacklogLimit) {
        return withOptions(options.withOutputBacklogLimit(outputBacklogLimit));
    }

    /**
     * Returns a copy with a request byte limit.
     *
     * @param maxRequestBytes request byte limit
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withMaxRequestBytes(int maxRequestBytes) {
        return withOptions(options.withMaxRequestBytes(maxRequestBytes));
    }

    /**
     * Returns a copy with a request character limit for text writes.
     *
     * @param maxRequestChars request character limit
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withMaxRequestChars(int maxRequestChars) {
        return withOptions(options.withMaxRequestChars(maxRequestChars));
    }

    /**
     * Returns a copy with a response byte limit.
     *
     * @param maxResponseBytes response byte limit
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withMaxResponseBytes(int maxResponseBytes) {
        return withOptions(options.withMaxResponseBytes(maxResponseBytes));
    }

    /**
     * Returns a copy with a response character limit for text reads.
     *
     * @param maxResponseChars response character limit
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withMaxResponseChars(int maxResponseChars) {
        return withOptions(options.withMaxResponseChars(maxResponseChars));
    }

    /**
     * Returns a copy with a protocol text charset policy.
     *
     * @param charsetPolicy charset policy
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withCharsetPolicy(CharsetPolicy charsetPolicy) {
        return withOptions(options.withCharsetPolicy(charsetPolicy));
    }

    /**
     * Returns a copy with explicit protocol-session options.
     *
     * @param options protocol-session options
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> withOptions(ProtocolSessionOptions options) {
        return new ProtocolSessionScenario<>(service, adapter, configure, options);
    }

    /**
     * Returns a copy configured by a scenario-specific callback.
     *
     * @param configure configuration callback
     * @return updated scenario
     */
    public ProtocolSessionScenario<I, O> configuredBy(Consumer<ProtocolSessionInvocation.Builder<I, O>> configure) {
        return with(configure);
    }

    /**
     * Opens one configured protocol session.
     *
     * @return protocol session
     */
    public ProtocolSession<I, O> open() {
        ProtocolSessionInvocation.Builder<I, O> builder = ProtocolSessionInvocation.builder(options);
        configure.accept(builder);
        return service.openProtocolSession(adapter, builder.build());
    }

    private ProtocolSessionScenario<I, O> with(Consumer<ProtocolSessionInvocation.Builder<I, O>> step) {
        Objects.requireNonNull(step, "step");
        return new ProtocolSessionScenario<>(service, adapter, configure.andThen(step), options);
    }
}
