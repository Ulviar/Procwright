package io.github.ulviar.icli.terminal;

import io.github.ulviar.icli.command.CommandExecutionException;
import io.github.ulviar.icli.internal.CommandValidation;
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
