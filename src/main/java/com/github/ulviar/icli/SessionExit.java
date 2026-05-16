package com.github.ulviar.icli;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Exit state of an interactive session process.
 *
 * @param exitCode process exit code when available
 * @param timedOut whether session lifecycle timeout stopped the process
 */
public record SessionExit(OptionalInt exitCode, boolean timedOut) {

    /**
     * Creates a session exit state.
     */
    public SessionExit {
        Objects.requireNonNull(exitCode, "exitCode");
    }
}
