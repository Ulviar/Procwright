package com.github.ulviar.icli;

import com.github.ulviar.icli.command.ShutdownPolicy;
import com.github.ulviar.icli.session.Session;
import com.github.ulviar.icli.session.SessionInvocation;
import com.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Configures and opens a raw interactive process session.
 */
public final class InteractiveScenario {

    private final CommandService service;
    private final Consumer<SessionInvocation.Builder> configure;

    InteractiveScenario(CommandService service) {
        this(service, builder -> {});
    }

    private InteractiveScenario(CommandService service, Consumer<SessionInvocation.Builder> configure) {
        this.service = Objects.requireNonNull(service, "service");
        this.configure = Objects.requireNonNull(configure, "configure");
    }

    /**
     * Returns a copy with one additional argv argument.
     *
     * @param argument argument value
     * @return updated scenario
     */
    public InteractiveScenario withArg(String argument) {
        return with(builder -> builder.arg(argument));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public InteractiveScenario withArgs(String... arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public InteractiveScenario withArgs(Collection<String> arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with a per-session working directory.
     *
     * @param workingDirectory working directory
     * @return updated scenario
     */
    public InteractiveScenario withWorkingDirectory(Path workingDirectory) {
        return with(builder -> builder.workingDirectory(workingDirectory));
    }

    /**
     * Returns a copy with one environment override.
     *
     * @param name environment variable name
     * @param value environment variable value
     * @return updated scenario
     */
    public InteractiveScenario withEnvironment(String name, String value) {
        return with(builder -> builder.putEnvironment(name, value));
    }

    /**
     * Returns a copy that inherits the current process environment before applying overrides.
     *
     * @return updated scenario
     */
    public InteractiveScenario withInheritedEnvironment() {
        return with(SessionInvocation.Builder::inheritEnvironment);
    }

    /**
     * Returns a copy that starts with only configured environment overrides.
     *
     * @return updated scenario
     */
    public InteractiveScenario withCleanEnvironment() {
        return with(SessionInvocation.Builder::cleanEnvironment);
    }

    /**
     * Returns a copy with a per-session shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated scenario
     */
    public InteractiveScenario withShutdown(ShutdownPolicy shutdownPolicy) {
        return with(builder -> builder.shutdown(shutdownPolicy));
    }

    /**
     * Returns a copy with a caller-visible idle timeout.
     *
     * @param idleTimeout idle timeout, or {@link Duration#ZERO} to disable it
     * @return updated scenario
     */
    public InteractiveScenario withIdleTimeout(Duration idleTimeout) {
        return with(builder -> builder.idleTimeout(idleTimeout));
    }

    /**
     * Returns a copy with a text-send charset.
     *
     * @param charset text-send charset
     * @return updated scenario
     */
    public InteractiveScenario withCharset(Charset charset) {
        return with(builder -> builder.charset(charset));
    }

    /**
     * Returns a copy with a terminal policy.
     *
     * @param terminalPolicy terminal policy
     * @return updated scenario
     */
    public InteractiveScenario withTerminal(TerminalPolicy terminalPolicy) {
        return with(builder -> builder.terminal(terminalPolicy));
    }

    /**
     * Returns a copy with a readiness probe.
     *
     * @param readinessProbe readiness probe
     * @return updated scenario
     */
    public InteractiveScenario withReadiness(Consumer<Session> readinessProbe) {
        return with(builder -> builder.readiness(readinessProbe));
    }

    /**
     * Returns a copy with a readiness timeout.
     *
     * @param readinessTimeout readiness timeout
     * @return updated scenario
     */
    public InteractiveScenario withReadinessTimeout(Duration readinessTimeout) {
        return with(builder -> builder.readinessTimeout(readinessTimeout));
    }

    /**
     * Opens the configured interactive session.
     *
     * @return interactive session
     */
    public Session open() {
        return service.interactive(configure);
    }

    /**
     * Opens the session with additional argv arguments.
     *
     * @param arguments argument values
     * @return interactive session
     */
    public Session open(String... arguments) {
        return withArgs(arguments).open();
    }

    private InteractiveScenario with(Consumer<SessionInvocation.Builder> step) {
        Objects.requireNonNull(step, "step");
        return new InteractiveScenario(service, configure.andThen(step));
    }
}
