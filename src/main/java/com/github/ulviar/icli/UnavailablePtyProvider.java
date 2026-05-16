package com.github.ulviar.icli;

import java.util.Objects;

record UnavailablePtyProvider(String description) implements PtyProvider {

    UnavailablePtyProvider {
        CommandSpec.requireText(description, "description");
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
