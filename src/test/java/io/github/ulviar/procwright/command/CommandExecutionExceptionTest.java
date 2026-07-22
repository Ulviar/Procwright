/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

final class CommandExecutionExceptionTest {

    @Test
    void onlyDecodeFailureMayCarryCompletedResult() {
        CommandResult result = new CommandResult(0, "replacement text", "diagnostics");

        CommandExecutionException decodeFailure = new CommandExecutionException(
                CommandExecutionException.Reason.DECODE_ERROR, "malformed output", null, result);

        assertSame(result, decodeFailure.result().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandExecutionException(CommandExecutionException.Reason.DECODE_ERROR, "missing result"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandExecutionException(
                        CommandExecutionException.Reason.DECODE_ERROR, "missing result", new IllegalStateException()));
        for (CommandExecutionException.Reason reason :
                EnumSet.complementOf(EnumSet.of(CommandExecutionException.Reason.DECODE_ERROR))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CommandExecutionException(reason, "invalid result-bearing failure", null, result));
        }
    }
}
