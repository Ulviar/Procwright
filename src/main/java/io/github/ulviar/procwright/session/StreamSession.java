package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultStreamSession;
import java.util.concurrent.CompletableFuture;

/**
 * Handle for a listen-only streaming command.
 *
 * <p>A stream session owns stdout/stderr pumps and dispatches chunks to the configured listener. It does not retain all
 * output; only a bounded diagnostic window is kept for exit and failure signals.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive
 * stream sessions from {@code CommandService}.
 */
public sealed interface StreamSession extends AutoCloseable permits DefaultStreamSession {

    /**
     * Returns a process exit future view. The future completes after the process exits and output pumps drain.
     *
     * @return stream exit future
     */
    CompletableFuture<StreamExit> onExit();

    /**
     * Returns the current bounded diagnostic transcript snapshot.
     *
     * @return diagnostic transcript
     */
    StreamTranscript diagnostics();

    /**
     * Closes process stdin without writing input. This is useful when the stream was started with
     * {@link StreamInvocation.Builder#keepStdinOpen()}.
     */
    void closeStdin();

    /**
     * Stops the underlying process through the configured shutdown policy. Calling this method more than once has no
     * effect.
     */
    @Override
    void close();
}
