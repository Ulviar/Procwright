/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;

/**
 * Caller-requested exception view of a command result.
 *
 * <p>One-shot execution returns nonzero exits and ordinary timeouts as {@link CommandResult}s. It does not throw this
 * exception automatically. Use {@link CommandResult#toException()} when the application chooses to treat a result as
 * a failure. The exception retains the complete result; its message contains the exit/timeout summary, not output.
 */
@SuppressWarnings("serial")
public final class CommandException extends ProcwrightException {

    /** Command result that caused this exception. */
    private final CommandResult result;

    /**
     * Creates an exception for a command result.
     *
     * <p>The result is retained unchanged, including successful results; no success/failure validation is performed.
     *
     * @param result command result
     */
    public CommandException(CommandResult result) {
        super(message(Objects.requireNonNull(result, "result")));
        this.result = result;
    }

    /**
     * Returns the command result that caused this exception.
     *
     * @return command result
     */
    public CommandResult result() {
        return result;
    }

    private static String message(CommandResult result) {
        if (result.timedOut()) {
            return "Command timed out";
        }
        return result.exitCode().stream()
                .mapToObj(exitCode -> "Command exited with code " + exitCode)
                .findFirst()
                .orElse("Command did not produce an exit code");
    }
}
