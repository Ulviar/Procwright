/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Immutable readiness behavior for a session draft. */
public record ReadinessSettings<H>(Optional<Consumer<H>> probe, Duration timeout) {

    public ReadinessSettings {
        probe = Objects.requireNonNull(probe, "probe");
        timeout = DurationSupport.requirePositive(timeout, "readinessTimeout");
    }

    public static <H> ReadinessSettings<H> defaults() {
        return new ReadinessSettings<>(Optional.empty(), Duration.ofSeconds(5));
    }

    public ReadinessSettings<H> withProbe(Consumer<H> probe) {
        return new ReadinessSettings<>(Optional.of(Objects.requireNonNull(probe, "readinessProbe")), timeout);
    }

    public ReadinessSettings<H> withTimeout(Duration timeout) {
        return new ReadinessSettings<>(probe, timeout);
    }
}
