/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.function.Function;

final class SessionTestFixtures {

    private SessionTestFixtures() {}

    static DefaultSession open(Process process, Duration idleTimeout, ShutdownPolicy shutdownPolicy, Charset charset) {
        return open(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty()));
    }

    static DefaultSession open(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics) {
        return DefaultSession.openTransactionally(process, idleTimeout, shutdownPolicy, charset, diagnostics, () -> {});
    }

    static <T> T openHandle(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            SessionOutputMode outputMode,
            Function<? super DefaultSession, ? extends T> handleFactory) {
        return DefaultSession.openHelperTransactionally(
                process, idleTimeout, shutdownPolicy, charset, diagnostics, outputMode, handleFactory);
    }

    static <T> T openHandle(
            Process process,
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            DiagnosticEmitter diagnostics,
            SessionOutputMode outputMode,
            Function<? super DefaultSession, ? extends T> handleFactory,
            BoundedCloseDispatcher closeDispatcher,
            DefaultSession.WatcherStarter watcherStarter) {
        return DefaultSession.openHelperTransactionally(
                process,
                idleTimeout,
                shutdownPolicy,
                charset,
                diagnostics,
                outputMode,
                handleFactory,
                closeDispatcher,
                watcherStarter);
    }
}
