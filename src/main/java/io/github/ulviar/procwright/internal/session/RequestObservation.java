/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Measures one logical request while excluding time spent waiting to acquire a pooled worker. */
final class RequestObservation {

    private final LongSupplier clock;
    private final BiConsumer<Boolean, Long> completion;
    private long accumulatedNanos;
    private long segmentStartedNanos;
    private boolean paused;
    private boolean completed;

    RequestObservation(LongSupplier clock, BiConsumer<Boolean, Long> completion) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.completion = Objects.requireNonNull(completion, "completion");
        segmentStartedNanos = clock.getAsLong();
    }

    void pauseForAcquire() {
        if (completed || paused) {
            throw new IllegalStateException("request observation cannot be paused");
        }
        accumulatedNanos += elapsedSinceSegmentStart();
        paused = true;
    }

    void resumeAfterAcquire() {
        if (completed || !paused) {
            throw new IllegalStateException("request observation cannot be resumed");
        }
        paused = false;
        segmentStartedNanos = clock.getAsLong();
    }

    void succeed() {
        finish(true);
    }

    void fail() {
        finish(false);
    }

    private void finish(boolean successful) {
        if (completed) {
            return;
        }
        completed = true;
        if (!paused) {
            accumulatedNanos += elapsedSinceSegmentStart();
        }
        completion.accept(successful, accumulatedNanos);
    }

    private long elapsedSinceSegmentStart() {
        return Math.max(0, clock.getAsLong() - segmentStartedNanos);
    }
}
