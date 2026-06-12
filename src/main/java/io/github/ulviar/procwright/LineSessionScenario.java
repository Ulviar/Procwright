/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionInvocation;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.ResponseDecoder;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Configures a line-oriented request/response worker session.
 */
public final class LineSessionScenario {

    private final CommandService service;
    private final Consumer<LineSessionInvocation.Builder> configure;
    private final LineSessionOptions options;

    LineSessionScenario(CommandService service, LineSessionOptions options) {
        this(service, builder -> {}, options);
    }

    private LineSessionScenario(
            CommandService service, Consumer<LineSessionInvocation.Builder> configure, LineSessionOptions options) {
        this.service = Objects.requireNonNull(service, "service");
        this.configure = Objects.requireNonNull(configure, "configure");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Returns a copy with one additional argv argument.
     *
     * @param argument argument value
     * @return updated scenario
     */
    public LineSessionScenario withArg(String argument) {
        return with(builder -> builder.arg(argument));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public LineSessionScenario withArgs(String... arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with additional argv arguments.
     *
     * @param arguments argument values
     * @return updated scenario
     */
    public LineSessionScenario withArgs(Collection<String> arguments) {
        return with(builder -> builder.args(arguments));
    }

    /**
     * Returns a copy with a per-worker working directory.
     *
     * @param workingDirectory working directory
     * @return updated scenario
     */
    public LineSessionScenario withWorkingDirectory(Path workingDirectory) {
        return with(builder -> builder.workingDirectory(workingDirectory));
    }

    /**
     * Returns a copy with one per-worker environment override.
     *
     * @param name environment variable name
     * @param value environment variable value
     * @return updated scenario
     */
    public LineSessionScenario withEnvironment(String name, String value) {
        return with(builder -> builder.putEnvironment(name, value));
    }

    /**
     * Returns a copy that inherits the current process environment before applying overrides.
     *
     * @return updated scenario
     */
    public LineSessionScenario withInheritedEnvironment() {
        return with(LineSessionInvocation.Builder::inheritEnvironment);
    }

    /**
     * Returns a copy that starts with only configured environment overrides.
     *
     * @return updated scenario
     */
    public LineSessionScenario withCleanEnvironment() {
        return with(LineSessionInvocation.Builder::cleanEnvironment);
    }

    /**
     * Returns a copy with a per-worker shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated scenario
     */
    public LineSessionScenario withShutdown(ShutdownPolicy shutdownPolicy) {
        return with(builder -> builder.shutdown(shutdownPolicy));
    }

    /**
     * Returns a copy with a caller-visible idle timeout.
     *
     * @param idleTimeout idle timeout, or {@link Duration#ZERO} to disable it
     * @return updated scenario
     */
    public LineSessionScenario withIdleTimeout(Duration idleTimeout) {
        return with(builder -> builder.idleTimeout(idleTimeout));
    }

    /**
     * Returns a copy with a terminal policy.
     *
     * @param terminalPolicy terminal policy
     * @return updated scenario
     */
    public LineSessionScenario withTerminal(TerminalPolicy terminalPolicy) {
        return with(builder -> builder.terminal(terminalPolicy));
    }

    /**
     * Returns a copy with a readiness probe.
     *
     * @param readinessProbe readiness probe
     * @return updated scenario
     */
    public LineSessionScenario withReadiness(Consumer<LineSession> readinessProbe) {
        return with(builder -> builder.readiness(readinessProbe));
    }

    /**
     * Returns a copy with a readiness timeout.
     *
     * @param readinessTimeout readiness timeout
     * @return updated scenario
     */
    public LineSessionScenario withReadinessTimeout(Duration readinessTimeout) {
        return with(builder -> builder.readinessTimeout(readinessTimeout));
    }

    /**
     * Returns a copy with a default request timeout.
     *
     * @param requestTimeout request timeout
     * @return updated scenario
     */
    public LineSessionScenario withRequestTimeout(Duration requestTimeout) {
        return withOptions(options.withRequestTimeout(requestTimeout));
    }

    /**
     * Returns a copy with a retained transcript limit.
     *
     * @param transcriptLimit transcript limit
     * @return updated scenario
     */
    public LineSessionScenario withTranscriptLimit(int transcriptLimit) {
        return withOptions(options.withTranscriptLimit(transcriptLimit));
    }

    /**
     * Returns a copy with a pending stdout line backlog limit.
     *
     * <p>The limit counts pending response lines, not bytes.
     *
     * @param stdoutBacklogLines backlog limit in lines
     * @return updated scenario
     */
    public LineSessionScenario withStdoutBacklogLines(int stdoutBacklogLines) {
        return withOptions(options.withStdoutBacklogLines(stdoutBacklogLines));
    }

    /**
     * Returns a copy with a maximum single stdout line length.
     *
     * @param maxLineChars maximum line characters
     * @return updated scenario
     */
    public LineSessionScenario withMaxLineChars(int maxLineChars) {
        return withOptions(options.withMaxLineChars(maxLineChars));
    }

    /**
     * Returns a copy with a line protocol charset using replacement decoding.
     *
     * @param charset line protocol charset
     * @return updated scenario
     */
    public LineSessionScenario withCharset(Charset charset) {
        return withOptions(options.withCharset(charset));
    }

    /**
     * Returns a copy with a line protocol charset policy.
     *
     * @param charsetPolicy line protocol charset policy
     * @return updated scenario
     */
    public LineSessionScenario withCharsetPolicy(CharsetPolicy charsetPolicy) {
        return withOptions(options.withCharsetPolicy(charsetPolicy));
    }

    /**
     * Returns a copy with a default response decoder.
     *
     * @param responseDecoder response decoder
     * @return updated scenario
     */
    public LineSessionScenario withResponseDecoder(ResponseDecoder responseDecoder) {
        return withOptions(options.withResponseDecoder(responseDecoder));
    }

    /**
     * Returns a copy with explicit line-session options.
     *
     * @param options line-session options
     * @return updated scenario
     */
    public LineSessionScenario withOptions(LineSessionOptions options) {
        return new LineSessionScenario(service, configure, options);
    }

    /**
     * Returns a copy configured by a scenario-specific callback.
     *
     * @param configure configuration callback
     * @return updated scenario
     */
    public LineSessionScenario configuredBy(Consumer<LineSessionInvocation.Builder> configure) {
        return with(configure);
    }

    /**
     * Branches into pooled line-session mode.
     *
     * @return pooled line-session scenario
     */
    public PooledLineSessionScenario pooled() {
        return new PooledLineSessionScenario(service, configure, options, service.pooledLineSessionOptions());
    }

    /**
     * Opens one configured line session.
     *
     * @return line session
     */
    public LineSession open() {
        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();
        configure.accept(builder);
        return service.openLineSession(builder.build(), options);
    }

    /**
     * Opens one line session with additional argv arguments.
     *
     * @param arguments argument values
     * @return line session
     */
    public LineSession open(String... arguments) {
        return withArgs(arguments).open();
    }

    private LineSessionScenario with(Consumer<LineSessionInvocation.Builder> step) {
        Objects.requireNonNull(step, "step");
        return new LineSessionScenario(service, configure.andThen(step), options);
    }
}
