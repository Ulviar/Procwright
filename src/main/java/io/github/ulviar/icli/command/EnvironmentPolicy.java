package io.github.ulviar.icli.command;

/**
 * Controls how a child process environment is assembled.
 */
public enum EnvironmentPolicy {
    /**
     * Starts from the current process environment and applies configured overrides.
     */
    INHERIT,

    /**
     * Starts from an empty environment and applies only configured overrides.
     */
    CLEAN
}
