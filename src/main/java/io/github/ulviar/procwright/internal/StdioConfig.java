/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;

/**
 * Operating-system level stdio redirections applied at process launch.
 *
 * <p>The one-shot kernel derives redirections from the resolved {@code CapturePolicy} and {@code CommandInput}.
 * {@link #pipes()} selects pipes for every stream and is also used for ordinary session launches. A PTY provider
 * constructs its own transport rather than using this redirect configuration for the terminal child.
 */
public record StdioConfig(
        ProcessBuilder.Redirect stdin, ProcessBuilder.Redirect stdout, ProcessBuilder.Redirect stderr) {

    private static final StdioConfig PIPES =
            new StdioConfig(ProcessBuilder.Redirect.PIPE, ProcessBuilder.Redirect.PIPE, ProcessBuilder.Redirect.PIPE);

    public StdioConfig {
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    public static StdioConfig pipes() {
        return PIPES;
    }
}
