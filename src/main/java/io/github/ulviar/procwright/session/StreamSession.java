/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultStreamSession;
import java.util.concurrent.CompletableFuture;

/**
 * Handle for a listen-only streaming command.
 *
 * <p>A stream session owns stdout/stderr pumps and dispatches chunks to the configured listener. It does not retain all
 * output; only a bounded diagnostic window is kept for exit and failure signals. Process stdin is already closed when
 * the handle is returned.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive
 * stream sessions from {@code CommandService}.
 */
public sealed interface StreamSession extends AutoCloseable permits DefaultStreamSession {

    /**
     * Returns a process exit future view. After natural process exit completes this future, all listener calls have
     * returned and no later call can begin. Explicit close and timeout admit no further deliveries, but a delivery
     * admitted immediately before stopping may invoke or remain inside the listener after this future completes.
     * Potentially blocking physical stream closes continue independently. Natural completion waits for stdout/stderr EOF;
     * configure the scenario timeout when a descendant may inherit and keep either pipe open after the root process
     * exits.
     *
     * <p>Listener {@link RuntimeException}s, output I/O failures, and other ordinary callback failures complete the future
     * exceptionally with {@link StreamException}. A fatal {@link Error} is not wrapped: the future completes
     * exceptionally with the same {@code Error} instance.
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
     * Stops the underlying process through the configured shutdown policy. Calling this method more than once has no
     * effect.
     */
    @Override
    void close();
}
