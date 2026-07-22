/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.util.Objects;

/** Immutable terminal capability request for a session launch. */
public record TerminalSettings(TerminalPolicy policy, PtyProvider provider, TerminalSize size) {

    public TerminalSettings {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(size, "size");
    }

    public static TerminalSettings defaults() {
        return new TerminalSettings(TerminalPolicy.DISABLED, PtyProvider.system(), TerminalSize.defaults());
    }

    public TerminalSettings withPolicy(TerminalPolicy policy) {
        return new TerminalSettings(policy, provider, size);
    }

    public TerminalSettings withProvider(PtyProvider provider) {
        return new TerminalSettings(policy, provider, size);
    }

    public TerminalSettings withSize(TerminalSize size) {
        return new TerminalSettings(policy, provider, size);
    }
}
