package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.PooledProtocolSessionInvocation;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionInvocation;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

final class ProtocolWorkerConfiguration<I, O> {

    private final Consumer<ProtocolWorkerConfigurator<I, O>> configure;

    private ProtocolWorkerConfiguration(Consumer<ProtocolWorkerConfigurator<I, O>> configure) {
        this.configure = Objects.requireNonNull(configure, "configure");
    }

    static <I, O> ProtocolWorkerConfiguration<I, O> empty() {
        return new ProtocolWorkerConfiguration<>(configurator -> {});
    }

    ProtocolWorkerConfiguration<I, O> andThen(Consumer<ProtocolWorkerConfigurator<I, O>> next) {
        Objects.requireNonNull(next, "next");
        return new ProtocolWorkerConfiguration<>(configurator -> {
            configure.accept(configurator);
            next.accept(configurator);
        });
    }

    Consumer<ProtocolSessionInvocation.Builder<I, O>> forSingleWorker() {
        return builder -> configure.accept(new SingleWorkerConfigurator<>(builder));
    }

    Consumer<PooledProtocolSessionInvocation.Builder<I, O>> forPooledWorker() {
        return builder -> configure.accept(new PooledWorkerConfigurator<>(builder));
    }

    interface ProtocolWorkerConfigurator<I, O> {

        void arg(String argument);

        void args(String... arguments);

        void args(Collection<String> arguments);

        void workingDirectory(Path workingDirectory);

        void putEnvironment(String name, String value);

        void inheritEnvironment();

        void cleanEnvironment();

        void shutdown(ShutdownPolicy shutdownPolicy);

        void idleTimeout(Duration idleTimeout);

        void terminal(TerminalPolicy terminalPolicy);

        void readiness(Consumer<ProtocolSession<I, O>> readinessProbe);

        void readinessTimeout(Duration readinessTimeout);

        void requestTimeout(Duration requestTimeout);

        void transcriptLimit(int transcriptLimit);

        void outputBacklogLimit(int outputBacklogLimit);

        void maxRequestBytes(int maxRequestBytes);

        void maxRequestChars(int maxRequestChars);

        void maxResponseBytes(int maxResponseBytes);

        void maxResponseChars(int maxResponseChars);

        void charsetPolicy(CharsetPolicy charsetPolicy);
    }

    private record SingleWorkerConfigurator<I, O>(ProtocolSessionInvocation.Builder<I, O> builder)
            implements ProtocolWorkerConfigurator<I, O> {

        SingleWorkerConfigurator {
            Objects.requireNonNull(builder, "builder");
        }

        @Override
        public void arg(String argument) {
            builder.arg(argument);
        }

        @Override
        public void args(String... arguments) {
            builder.args(arguments);
        }

        @Override
        public void args(Collection<String> arguments) {
            builder.args(arguments);
        }

        @Override
        public void workingDirectory(Path workingDirectory) {
            builder.workingDirectory(workingDirectory);
        }

        @Override
        public void putEnvironment(String name, String value) {
            builder.putEnvironment(name, value);
        }

        @Override
        public void inheritEnvironment() {
            builder.inheritEnvironment();
        }

        @Override
        public void cleanEnvironment() {
            builder.cleanEnvironment();
        }

        @Override
        public void shutdown(ShutdownPolicy shutdownPolicy) {
            builder.shutdown(shutdownPolicy);
        }

        @Override
        public void idleTimeout(Duration idleTimeout) {
            builder.idleTimeout(idleTimeout);
        }

        @Override
        public void terminal(TerminalPolicy terminalPolicy) {
            builder.terminal(terminalPolicy);
        }

        @Override
        public void readiness(Consumer<ProtocolSession<I, O>> readinessProbe) {
            builder.readiness(readinessProbe);
        }

        @Override
        public void readinessTimeout(Duration readinessTimeout) {
            builder.readinessTimeout(readinessTimeout);
        }

        @Override
        public void requestTimeout(Duration requestTimeout) {
            builder.requestTimeout(requestTimeout);
        }

        @Override
        public void transcriptLimit(int transcriptLimit) {
            builder.transcriptLimit(transcriptLimit);
        }

        @Override
        public void outputBacklogLimit(int outputBacklogLimit) {
            builder.outputBacklogLimit(outputBacklogLimit);
        }

        @Override
        public void maxRequestBytes(int maxRequestBytes) {
            builder.maxRequestBytes(maxRequestBytes);
        }

        @Override
        public void maxRequestChars(int maxRequestChars) {
            builder.maxRequestChars(maxRequestChars);
        }

        @Override
        public void maxResponseBytes(int maxResponseBytes) {
            builder.maxResponseBytes(maxResponseBytes);
        }

        @Override
        public void maxResponseChars(int maxResponseChars) {
            builder.maxResponseChars(maxResponseChars);
        }

        @Override
        public void charsetPolicy(CharsetPolicy charsetPolicy) {
            builder.charsetPolicy(charsetPolicy);
        }
    }

    private record PooledWorkerConfigurator<I, O>(PooledProtocolSessionInvocation.Builder<I, O> builder)
            implements ProtocolWorkerConfigurator<I, O> {

        PooledWorkerConfigurator {
            Objects.requireNonNull(builder, "builder");
        }

        @Override
        public void arg(String argument) {
            builder.arg(argument);
        }

        @Override
        public void args(String... arguments) {
            builder.args(arguments);
        }

        @Override
        public void args(Collection<String> arguments) {
            builder.args(arguments);
        }

        @Override
        public void workingDirectory(Path workingDirectory) {
            builder.workingDirectory(workingDirectory);
        }

        @Override
        public void putEnvironment(String name, String value) {
            builder.putEnvironment(name, value);
        }

        @Override
        public void inheritEnvironment() {
            builder.inheritEnvironment();
        }

        @Override
        public void cleanEnvironment() {
            builder.cleanEnvironment();
        }

        @Override
        public void shutdown(ShutdownPolicy shutdownPolicy) {
            builder.shutdown(shutdownPolicy);
        }

        @Override
        public void idleTimeout(Duration idleTimeout) {
            builder.idleTimeout(idleTimeout);
        }

        @Override
        public void terminal(TerminalPolicy terminalPolicy) {
            builder.terminal(terminalPolicy);
        }

        @Override
        public void readiness(Consumer<ProtocolSession<I, O>> readinessProbe) {
            builder.readiness(readinessProbe);
        }

        @Override
        public void readinessTimeout(Duration readinessTimeout) {
            builder.readinessTimeout(readinessTimeout);
        }

        @Override
        public void requestTimeout(Duration requestTimeout) {
            builder.requestTimeout(requestTimeout);
        }

        @Override
        public void transcriptLimit(int transcriptLimit) {
            builder.transcriptLimit(transcriptLimit);
        }

        @Override
        public void outputBacklogLimit(int outputBacklogLimit) {
            builder.outputBacklogLimit(outputBacklogLimit);
        }

        @Override
        public void maxRequestBytes(int maxRequestBytes) {
            builder.maxRequestBytes(maxRequestBytes);
        }

        @Override
        public void maxRequestChars(int maxRequestChars) {
            builder.maxRequestChars(maxRequestChars);
        }

        @Override
        public void maxResponseBytes(int maxResponseBytes) {
            builder.maxResponseBytes(maxResponseBytes);
        }

        @Override
        public void maxResponseChars(int maxResponseChars) {
            builder.maxResponseChars(maxResponseChars);
        }

        @Override
        public void charsetPolicy(CharsetPolicy charsetPolicy) {
            builder.charsetPolicy(charsetPolicy);
        }
    }
}
