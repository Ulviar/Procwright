/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Owns stable references to one process's streams and their exact-once logical close.
 *
 * @hidden
 */
public final class OwnedStreams {

    private final OwnedStream<OutputStream> stdin;
    private final OwnedStream<InputStream> stdout;
    private final OwnedStream<InputStream> stderr;

    private OwnedStreams(
            OwnedStream<OutputStream> stdin, OwnedStream<InputStream> stdout, OwnedStream<InputStream> stderr) {
        this.stdin = Objects.requireNonNull(stdin, "stdin");
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.stderr = Objects.requireNonNull(stderr, "stderr");
    }

    public static OwnedStreams acquire(Process process) {
        Objects.requireNonNull(process, "process");
        OwnedStream<OutputStream> stdin = null;
        OwnedStream<InputStream> stdout = null;
        OwnedStream<InputStream> stderr = null;
        try {
            stdin = new OwnedStream<>("stdin", Objects.requireNonNull(process.getOutputStream(), "process stdin"));
            stdout = new OwnedStream<>("stdout", Objects.requireNonNull(process.getInputStream(), "process stdout"));
            stderr = new OwnedStream<>("stderr", Objects.requireNonNull(process.getErrorStream(), "process stderr"));
            return new OwnedStreams(stdin, stdout, stderr);
        } catch (RuntimeException | Error failure) {
            close(stderr);
            close(stdout);
            close(stdin);
            throw failure;
        }
    }

    public OwnedStream<OutputStream> stdin() {
        return stdin;
    }

    public OwnedStream<InputStream> stdout() {
        return stdout;
    }

    public OwnedStream<InputStream> stderr() {
        return stderr;
    }

    public void closeAll() {
        stdin.close();
        stdout.close();
        stderr.close();
    }

    private static void close(OwnedStream<?> stream) {
        if (stream != null) {
            stream.close();
        }
    }
}
