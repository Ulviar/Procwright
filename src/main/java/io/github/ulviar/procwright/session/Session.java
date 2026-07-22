/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.internal.session.DefaultSession;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * Raw handle for an interactive command process.
 *
 * <p>A session exposes process streams directly and owns process lifecycle coordination. It does not serialize
 * line-oriented request/response workflows; higher-level scenarios should build those guarantees on top of this raw
 * handle.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive
 * session instances from {@code CommandService}; custom implementations are not supported because higher-level helpers
 * rely on Procwright's internal output-ownership invariant.
 */
public sealed interface Session extends AutoCloseable permits DefaultSession {

    /**
     * Returns raw process stdout.
     *
     * <p>Obtaining the stream wrapper does not claim output ownership. The first effective stream operation, including a
     * read, inspection, mark, reset, or close, atomically selects raw caller ownership for both process output streams.
     * If a higher-level helper already owns output, that operation throws {@link IllegalStateException}; after raw
     * ownership is selected, opening such a helper throws the same exception.
     *
     * @return stdout stream
     */
    InputStream stdout();

    /**
     * Returns raw process stderr.
     *
     * <p>Obtaining the stream wrapper does not claim output ownership. The first effective operation follows the same
     * ownership rules as {@link #stdout()}; do not combine raw operations with a Procwright helper that drains output.
     *
     * @return stderr stream
     */
    InputStream stderr();

    /**
     * Returns raw process stdin guarded by the session lifecycle state.
     *
     * @return stdin stream
     */
    OutputStream stdin();

    /**
     * Writes text using the session charset and flushes stdin.
     *
     * @param text text to write
     */
    void send(String text);

    /**
     * Writes a line feed terminated text line using the session charset and flushes stdin.
     *
     * @param line line text without the terminating line feed
     */
    void sendLine(String line);

    /**
     * Writes explicit command input bytes and flushes stdin.
     *
     * @param input input bytes
     */
    void send(CommandInput input);

    /**
     * Writes a terminal control signal and flushes stdin.
     *
     * @param signal terminal signal
     */
    void sendSignal(TerminalSignal signal);

    /**
     * Closes process stdin. The session may keep running until the process exits or is closed.
     *
     * <p>The close has bounded execution admission before the session is returned, but its physical stream operation
     * is asynchronous because a concurrent write may hold the stream monitor indefinitely.
     */
    void closeStdin();

    /**
     * Returns an isolated view of the session's terminal future.
     *
     * <p>Completion follows process-tree cleanup, both physical stdout and stderr close attempts, and, when a Procwright
     * output helper owns those streams, both helper pump tasks and final close-failure aggregation. The internal process
     * outcome does not wait on helper cleanup, so helper observation cannot form a lifecycle dependency cycle; only this
     * public view applies the full cleanup barrier. A helper failure does not replace the raw process outcome.
     *
     * <p>A failure from a caller's inline raw-output close becomes the terminal session failure, by identity, if no other
     * terminal failure already owns the session. An inline close already in flight remains part of the public barrier and
     * can therefore replace a selected natural-success outcome until that physical close settles. Existing primary
     * failures remain primary and retain later cleanup failures through suppression; other late asynchronous failures do
     * not rewrite a published outcome.
     *
     * <p>The barrier does not wait for a physical stdin close blocked by a concurrent write. Caller-side completion or
     * cancellation of the returned view cannot affect the lifecycle owner. Synchronous continuations run on bounded
     * lifecycle-publication capacity only after cleanup has crossed the barrier, so hostile continuation code cannot pin
     * a process watcher, output pump, or physical-close owner.
     *
     * @return process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Returns an immutable expect configuration draft using default options.
     *
     * <p>Creating or configuring the draft does not claim output ownership. {@link Expect.Draft#open()} claims both output
     * streams and may fail with {@link IllegalStateException} if raw code, another helper, or session cleanup selected the
     * ownership mode first.
     *
     * @return immutable expect draft
     */
    default Expect.Draft expect() {
        return new ImmutableExpectDraft(this, io.github.ulviar.procwright.internal.ExpectSettings.defaults());
    }

    /**
     * Stops the process through the configured shutdown policy. Calling this method more than once has no effect.
     * Potentially blocking physical stream closes run asynchronously under the contract documented by
     * {@link #onExit()}.
     */
    @Override
    void close();
}
