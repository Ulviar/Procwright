package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandInput;
import java.util.Objects;

/**
 * @hidden
 */
public record StdinPolicy(Mode mode, CommandInput input) {

    private static final CommandInput EMPTY_INPUT = CommandInput.bytes(new byte[0]);

    public StdinPolicy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(input, "input");
        if (mode != Mode.INPUT && !input.equals(EMPTY_INPUT)) {
            throw new IllegalArgumentException("only input stdin can carry bytes");
        }
    }

    public static StdinPolicy closed() {
        return new StdinPolicy(Mode.CLOSED, EMPTY_INPUT);
    }

    public static StdinPolicy input(CommandInput input) {
        return new StdinPolicy(Mode.INPUT, input);
    }

    public static StdinPolicy open() {
        return new StdinPolicy(Mode.OPEN, EMPTY_INPUT);
    }

    public enum Mode {
        CLOSED,
        INPUT,
        OPEN
    }
}
