/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.ProcessStreamResource;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Consumer;

/** Atomically reserves physical close of both helper-owned process output streams. */
final class OutputCloseReservation {

    private final Object lock = new Object();
    private Reservation reservation;

    Reservation reserve(CloseOnceInputStream stdout, CloseOnceInputStream stderr, Consumer<Stream> pumpCloseObserver) {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(pumpCloseObserver, "pumpCloseObserver");
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
            reservation = new Reservation(this, stdout.resource(), stderr.resource(), pumpCloseObserver);
            return reservation;
        }
    }

    boolean claimOrdinaryClose(Stream stream, CloseOnceInputStream input) {
        Consumer<Stream> observer;
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
            observer = reservation.pumpCloseObserver;
        }
        observer.accept(stream);
        return false;
    }

    enum Stream {
        STDOUT,
        STDERR
    }

    static final class Reservation {

        private final OutputCloseReservation owner;
        private final ProcessStreamResource<InputStream> stdout;
        private final ProcessStreamResource<InputStream> stderr;
        private final Consumer<Stream> pumpCloseObserver;

        private Reservation(
                OutputCloseReservation owner,
                ProcessStreamResource<InputStream> stdout,
                ProcessStreamResource<InputStream> stderr,
                Consumer<Stream> pumpCloseObserver) {
            this.owner = owner;
            this.stdout = stdout;
            this.stderr = stderr;
            this.pumpCloseObserver = pumpCloseObserver;
        }

        void dispatchClose(
                Stream stream,
                String threadPrefix,
                java.util.function.Consumer<? super Throwable> failureHandler,
                Runnable completionHandler) {
            requireActive();
            resource(stream).closeOwnedAsync(threadPrefix, failureHandler, completionHandler);
        }

        void dispatchPair(
                String stdoutThreadPrefix,
                java.util.function.Consumer<? super Throwable> stdoutFailureHandler,
                Runnable stdoutCompletionHandler,
                String stderrThreadPrefix,
                java.util.function.Consumer<? super Throwable> stderrFailureHandler,
                Runnable stderrCompletionHandler) {
            requireActive();
            ProcessStreamResource.closePairAsync(
                    stdout,
                    stdoutThreadPrefix,
                    stdoutFailureHandler,
                    stdoutCompletionHandler,
                    stderr,
                    stderrThreadPrefix,
                    stderrFailureHandler,
                    stderrCompletionHandler);
        }

        private void requireActive() {
            synchronized (owner.lock) {
                if (owner.reservation != this) {
                    throw new IllegalStateException("Process output close token is not active");
                }
            }
        }

        private ProcessStreamResource<InputStream> resource(Stream stream) {
            return switch (stream) {
                case STDOUT -> stdout;
                case STDERR -> stderr;
            };
        }
    }
}
