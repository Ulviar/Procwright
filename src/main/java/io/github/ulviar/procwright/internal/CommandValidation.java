package io.github.ulviar.procwright.internal;

import java.util.Objects;

/**
 * @hidden
 */
public final class CommandValidation {

    private CommandValidation() {}

    public static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return requireNoNul(value, name);
    }

    public static String requireArgument(String argument) {
        return requireNoNul(Objects.requireNonNull(argument, "argument"), "argument");
    }

    public static String requireEnvironmentName(String name) {
        requireText(name, "environment name");
        if (name.indexOf('=') >= 0) {
            throw new IllegalArgumentException("environment name must not contain '='");
        }
        return name;
    }

    public static String requireEnvironmentValue(String value) {
        return requireNoNul(Objects.requireNonNull(value, "value"), "environment value");
    }

    public static String requireNoNul(String value, String name) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must not contain NUL");
        }
        return value;
    }
}
