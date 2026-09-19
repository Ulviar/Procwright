/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.internal.ProcessKernel;

/**
 * Creates immutable command services for launching external programs.
 *
 * <p>Choose a scenario on the returned {@link CommandService}, configure its immutable draft, then call
 * {@code execute()} for a finite result or {@code open()} for a live handle. Configuration never starts a process;
 * services and drafts can be reused. Shared callbacks have the concurrency requirements documented on each draft.
 *
 * <p>A finite command with explicit failure handling:
 * {@snippet file="io/github/ulviar/procwright/examples/ApiUsageExamples.java" region="run"}
 *
 * <p>Output capture is bounded; inspect {@link io.github.ulviar.procwright.command.CommandResult#stdoutTruncated()}
 * when complete output matters, or redirect large output to files with
 * {@link io.github.ulviar.procwright.command.CapturePolicy#toPath(java.nio.file.Path, java.nio.file.Path)}.
 */
public final class Procwright {

    private Procwright() {}

    /**
     * Creates a command service for one executable name or path, without launching it.
     * The string is not split into arguments or interpreted by a shell. Append arguments on the chosen draft,
     * or use {@link CommandSpec#shell(String)} explicitly when shell interpretation is required.
     *
     * @param executable nonblank executable name or path without NUL
     * @return reusable command service
     * @throws IllegalArgumentException if {@code executable} is blank or contains NUL
     */
    public static CommandService command(String executable) {
        return create(CommandSpec.of(executable));
    }

    /**
     * Creates a command service from an immutable command specification without launching it.
     * The specification supplies the initial arguments, directory, environment, and direct-or-shell mode for
     * every scenario draft selected from this service.
     *
     * @param commandSpec command specification
     * @return reusable command service
     */
    public static CommandService command(CommandSpec commandSpec) {
        return create(java.util.Objects.requireNonNull(commandSpec, "commandSpec"));
    }

    private static CommandService create(CommandSpec commandSpec) {
        return new CommandService(commandSpec, ProcessKernel.standard());
    }
}
