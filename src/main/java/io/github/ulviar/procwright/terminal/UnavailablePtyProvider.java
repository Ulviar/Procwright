/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.CommandValidation;
import java.util.Objects;

record UnavailablePtyProvider(String description) implements PtyProvider {

    UnavailablePtyProvider {
        CommandValidation.requireText(description, "description");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Process start(PtyRequest request) {
        Objects.requireNonNull(request, "request");
        throw new CommandExecutionException("PTY provider is unavailable: " + description);
    }
}
