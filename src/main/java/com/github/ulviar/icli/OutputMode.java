package com.github.ulviar.icli;

/**
 * Defines how stdout and stderr are exposed in a one-shot command result.
 */
public enum OutputMode {
    /**
     * Captures stdout and stderr independently.
     */
    SEPARATE,

    /**
     * Redirects stderr into stdout and leaves {@link CommandResult#stderr()} empty.
     */
    MERGED
}
