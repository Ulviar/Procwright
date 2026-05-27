package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.StreamInvocation;
import io.github.ulviar.procwright.session.StreamListener;
import io.github.ulviar.procwright.session.StreamSession;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Configures and opens a listen-only streaming process.
 */
public final class StreamScenario {

    private final CommandService service;
    private final Consumer<StreamInvocation.Builder> configure;

    StreamScenario(CommandService service) {
        this(service, builder -> {});
    }

    private StreamScenario(CommandService service, Consumer<StreamInvocation.Builder> configure) {
        this.service = Objects.requireNonNull(service, "service");
        this.configure = Objects.requireNonNull(configure, "configure");
    }

    /**
     * Returns a copy with one additional argv argument.
     *
     * @param argument argument value
     * @return updated scenario
     */
    public StreamScenario withArg(String argument) {
        return with(builder -> builder.arg(argument));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public StreamScenario withArgs(String... arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public StreamScenario withArgs(Collection<String> arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with a per-stream working directory.
     *
     * @param workingDirectory working directory
     * @return updated scenario
     */
    public StreamScenario withWorkingDirectory(Path workingDirectory) {
        return with(builder -> builder.workingDirectory(workingDirectory));
    }

    /**
     * Returns a copy with one environment override.
     *
     * @param name environment variable name
     * @param value environment variable value
     * @return updated scenario
     */
    public StreamScenario withEnvironment(String name, String value) {
        return with(builder -> builder.putEnvironment(name, value));
    }

    /**
     * Returns a copy that inherits the current process environment before applying overrides.
     *
     * @return updated scenario
     */
    public StreamScenario withInheritedEnvironment() {
        return with(StreamInvocation.Builder::inheritEnvironment);
    }

    /**
     * Returns a copy that starts with only configured environment overrides.
     *
     * @return updated scenario
     */
    public StreamScenario withCleanEnvironment() {
        return with(StreamInvocation.Builder::cleanEnvironment);
    }

    /**
     * Returns a copy with a per-stream shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated scenario
     */
    public StreamScenario withShutdown(ShutdownPolicy shutdownPolicy) {
        return with(builder -> builder.shutdown(shutdownPolicy));
    }

    /**
     * Returns a copy with an absolute stream timeout.
     *
     * @param timeout timeout, or {@link Duration#ZERO} to disable it
     * @return updated scenario
     */
    public StreamScenario withTimeout(Duration timeout) {
        return with(builder -> builder.timeout(timeout));
    }

    /**
     * Returns a copy that keeps stdin open after the stream starts.
     *
     * @return updated scenario
     */
    public StreamScenario withOpenStdin() {
        return with(StreamInvocation.Builder::keepStdinOpen);
    }

    /**
     * Returns a copy that closes stdin when the stream starts.
     *
     * @return updated scenario
     */
    public StreamScenario withClosedStdinOnStart() {
        return with(StreamInvocation.Builder::closeStdinOnStart);
    }

    /**
     * Returns a copy with an output listener.
     *
     * @param listener output listener
     * @return updated scenario
     */
    public StreamScenario onOutput(StreamListener listener) {
        return with(builder -> builder.onOutput(listener));
    }

    /**
     * Returns a copy configured by a scenario-specific callback.
     *
     * <p>This is useful for reusable presets and integrations that already operate on invocation builders.
     *
     * @param configure configuration callback
     * @return updated scenario
     */
    public StreamScenario configuredBy(Consumer<StreamInvocation.Builder> configure) {
        return with(configure);
    }

    /**
     * Opens the configured stream.
     *
     * @return streaming session
     */
    public StreamSession open() {
        return service.listen(configure);
    }

    /**
     * Opens the stream with additional argv arguments.
     *
     * @param arguments argument values
     * @return streaming session
     */
    public StreamSession open(String... arguments) {
        return withArgs(arguments).open();
    }

    private StreamScenario with(Consumer<StreamInvocation.Builder> step) {
        Objects.requireNonNull(step, "step");
        return new StreamScenario(service, configure.andThen(step));
    }
}
