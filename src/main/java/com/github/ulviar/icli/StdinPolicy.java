package com.github.ulviar.icli;

import java.util.Objects;

record StdinPolicy(Mode mode, CommandInput input) {

    StdinPolicy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(input, "input");
        if (mode != Mode.INPUT && !input.equals(CommandInput.closed())) {
            throw new IllegalArgumentException("only input stdin can carry bytes");
        }
    }

    static StdinPolicy closed() {
        return new StdinPolicy(Mode.CLOSED, CommandInput.closed());
    }

    static StdinPolicy input(CommandInput input) {
        return new StdinPolicy(Mode.INPUT, input);
    }

    static StdinPolicy open() {
        return new StdinPolicy(Mode.OPEN, CommandInput.closed());
    }

    enum Mode {
        CLOSED,
        INPUT,
        OPEN
    }
}
