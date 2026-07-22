/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.internal.ProcessKernel;

/**
 * Static entry point for scenario-first command workflows.
 *
 * <p>Each {@code command(...)} call returns an immutable {@link CommandService} bound to one base command. The service exposes the
 * scenario catalog: one-shot {@code run()}, raw {@code interactive()} sessions, line-oriented {@code lineSession()},
 * typed {@code protocolSession(...)}, and listen-only {@code listen()} streaming.
 *
 * <pre>{@code
 * CommandResult result = Procwright.command("git")
 *         .run()
 *         .withArgs("status", "--short")
 *         .withTimeout(Duration.ofSeconds(10))
 *         .execute();
 *
 * if (!result.succeeded()) {
 *     throw result.toException();
 * }
 * }</pre>
 */
public final class Procwright {

    private Procwright() {}

    /**
     * Creates a command service for an executable.
     *
     * @param executable executable name or path
     * @return command service
     */
    public static CommandService command(String executable) {
        return create(CommandSpec.of(executable));
    }

    /**
     * Creates a command service from an explicit command specification.
     *
     * @param commandSpec command specification
     * @return command service
     */
    public static CommandService command(CommandSpec commandSpec) {
        return create(java.util.Objects.requireNonNull(commandSpec, "commandSpec"));
    }

    private static CommandService create(CommandSpec commandSpec) {
        return new CommandService(commandSpec, ProcessKernel.standard());
    }
}
