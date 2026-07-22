/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import static io.github.ulviar.procwright.command.CommandExecutionException.Reason.LAUNCH_FAILED;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;

public final class RunFailureExample {

    private RunFailureExample() {}

    public static CommandResult execute(String executable) {
        try {
            return Procwright.command(executable).run().execute();
        } catch (CommandExecutionException failure) {
            if (failure.reason() == LAUNCH_FAILED) {
                throw new IllegalStateException("Command could not be launched", failure);
            }
            throw failure;
        }
    }
}
