package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.diagnostics.DiagnosticsOptions;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.PooledLineSessionOptions;
import io.github.ulviar.procwright.session.PooledProtocolSessionOptions;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.session.SessionOptions;
import io.github.ulviar.procwright.session.StreamOptions;
import java.util.Objects;

record CommandServiceDefaults(
        RunOptions runOptions,
        SessionOptions sessionOptions,
        LineSessionOptions lineSessionOptions,
        StreamOptions streamOptions,
        PooledLineSessionOptions pooledLineSessionOptions,
        ProtocolSessionOptions protocolSessionOptions,
        PooledProtocolSessionOptions pooledProtocolSessionOptions,
        DiagnosticsOptions diagnosticsOptions) {

    CommandServiceDefaults {
        Objects.requireNonNull(runOptions, "runOptions");
        Objects.requireNonNull(sessionOptions, "sessionOptions");
        Objects.requireNonNull(lineSessionOptions, "lineSessionOptions");
        Objects.requireNonNull(streamOptions, "streamOptions");
        Objects.requireNonNull(pooledLineSessionOptions, "pooledLineSessionOptions");
        Objects.requireNonNull(protocolSessionOptions, "protocolSessionOptions");
        Objects.requireNonNull(pooledProtocolSessionOptions, "pooledProtocolSessionOptions");
        Objects.requireNonNull(diagnosticsOptions, "diagnosticsOptions");
    }

    static CommandServiceDefaults of(RunOptions runOptions) {
        return new CommandServiceDefaults(
                runOptions,
                SessionOptions.defaults(),
                LineSessionOptions.defaults(),
                StreamOptions.defaults(),
                PooledLineSessionOptions.defaults(),
                ProtocolSessionOptions.defaults(),
                PooledProtocolSessionOptions.defaults(),
                DiagnosticsOptions.defaults());
    }

    CommandServiceDefaults withRunOptions(RunOptions runOptions) {
        return new CommandServiceDefaults(
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                protocolSessionOptions,
                pooledProtocolSessionOptions,
                diagnosticsOptions);
    }

    CommandServiceDefaults withSessionOptions(SessionOptions sessionOptions) {
        return new CommandServiceDefaults(
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                protocolSessionOptions,
                pooledProtocolSessionOptions,
                diagnosticsOptions);
    }

    CommandServiceDefaults withLineSessionOptions(LineSessionOptions lineSessionOptions) {
        return new CommandServiceDefaults(
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                protocolSessionOptions,
                pooledProtocolSessionOptions,
                diagnosticsOptions);
    }

    CommandServiceDefaults withStreamOptions(StreamOptions streamOptions) {
        return new CommandServiceDefaults(
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                protocolSessionOptions,
                pooledProtocolSessionOptions,
                diagnosticsOptions);
    }

    CommandServiceDefaults withPooledLineSessionOptions(PooledLineSessionOptions pooledLineSessionOptions) {
        return new CommandServiceDefaults(
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                protocolSessionOptions,
                pooledProtocolSessionOptions,
                diagnosticsOptions);
    }

    CommandServiceDefaults withProtocolSessionOptions(ProtocolSessionOptions protocolSessionOptions) {
        return new CommandServiceDefaults(
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                protocolSessionOptions,
                pooledProtocolSessionOptions,
                diagnosticsOptions);
    }

    CommandServiceDefaults withPooledProtocolSessionOptions(PooledProtocolSessionOptions pooledProtocolSessionOptions) {
        return new CommandServiceDefaults(
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                protocolSessionOptions,
                pooledProtocolSessionOptions,
                diagnosticsOptions);
    }

    CommandServiceDefaults withDiagnosticsOptions(DiagnosticsOptions diagnosticsOptions) {
        return new CommandServiceDefaults(
                runOptions,
                sessionOptions,
                lineSessionOptions,
                streamOptions,
                pooledLineSessionOptions,
                protocolSessionOptions,
                pooledProtocolSessionOptions,
                diagnosticsOptions);
    }
}
