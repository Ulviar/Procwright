/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandSpec;

/**
 * Static entry point for scenario-first command workflows.
 *
 * <p>Each factory returns an immutable {@link CommandService} bound to one base command. The service exposes the
 * scenario catalog: one-shot {@code run()}, raw {@code interactive()} sessions, line-oriented {@code lineSession()},
 * typed {@code protocolSession(...)}, and listen-only {@code listen()} streaming.
 *
 * <pre>{@code
 * CommandResult result = Procwright.command("git")
 *         .run()
 *         .withTimeout(Duration.ofSeconds(10))
 *         .execute("status", "--short");
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
        return CommandService.forCommand(executable);
    }

    /**
     * Creates a command service from an explicit command specification.
     *
     * @param commandSpec command specification
     * @return command service
     */
    public static CommandService command(CommandSpec commandSpec) {
        return CommandService.forCommand(commandSpec);
    }

    /**
     * Creates a command service for a command line interpreted by the system shell.
     *
     * <p>Prefer {@link #command(String)} plus argv arguments for untrusted values unless shell syntax is required.
     *
     * @param commandLine command line interpreted by the system shell
     * @return command service
     */
    public static CommandService shellCommand(String commandLine) {
        return CommandService.forShellCommand(commandLine);
    }
}
