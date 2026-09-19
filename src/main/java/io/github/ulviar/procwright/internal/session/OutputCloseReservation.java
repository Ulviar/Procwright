/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.ProcessStreamResource;
import java.io.InputStream;
import java.util.Objects;

/** Atomically reserves physical close of both helper-owned process output streams. */
final class OutputCloseReservation {

    private final Object lock = new Object();
    private Reservation reservation;

    Reservation reserve(CloseOnceInputStream stdout, CloseOnceInputStream stderr) {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        synchronized (lock) {
            if (!stdout.belongsTo(this, Stream.STDOUT) || !stderr.belongsTo(this, Stream.STDERR)) {
                throw new IllegalArgumentException("Both output streams must belong to this reservation");
            }
            if (reservation != null) {
                throw new IllegalStateException("Process output close is already reserved");
            }
            if (stdout.closeStarted() || stderr.closeStarted()) {
                throw new IllegalStateException("Process output close has already started");
            }
            reservation = new Reservation(stdout.resource(), stderr.resource());
            return reservation;
        }
    }

    boolean claimOrdinaryClose(Stream stream, CloseOnceInputStream input) {
        synchronized (lock) {
            if (reservation == null) {
                return true;
            }
            if (!input.belongsTo(this, stream)) {
                throw new IllegalArgumentException("Output stream does not belong to this reservation");
            }
            if (input.resource() != reservation.resource(stream)) {
                throw new IllegalArgumentException("Output stream is not owned by the active reservation");
            }
            return false;
        }
    }

    enum Stream {
        STDOUT,
        STDERR
    }

    static final class Reservation {

        private final ProcessStreamResource<InputStream> stdout;
        private final ProcessStreamResource<InputStream> stderr;

        private Reservation(ProcessStreamResource<InputStream> stdout, ProcessStreamResource<InputStream> stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        void dispatchPair(
                String stdoutThreadPrefix,
                java.util.function.Consumer<? super Throwable> stdoutFailureHandler,
                String stderrThreadPrefix,
                java.util.function.Consumer<? super Throwable> stderrFailureHandler) {
            ProcessStreamResource.closePairAsync(
                    stdout, stdoutThreadPrefix, stdoutFailureHandler, stderr, stderrThreadPrefix, stderrFailureHandler);
        }

        private ProcessStreamResource<InputStream> resource(Stream stream) {
            return switch (stream) {
                case STDOUT -> stdout;
                case STDERR -> stderr;
            };
        }
    }
}
