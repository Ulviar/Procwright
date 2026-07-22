/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

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
            reservation = new Reservation(this, stdout, stderr, pumpCloseObserver);
            return reservation;
        }
    }

    boolean claimOrdinaryClose(Stream stream, CloseOnceInputStream input) {
        Consumer<Stream> observer = null;
        synchronized (lock) {
            if (reservation == null) {
                return true;
            }
            reservation.requireStream(stream, input);
            if (reservation.markPumpClosed(stream)) {
                observer = reservation.pumpCloseObserver;
            }
        }
        if (observer != null) {
            observer.accept(stream);
        }
        return false;
    }

    enum Stream {
        STDOUT,
        STDERR
    }

    static final class Reservation {

        private final OutputCloseReservation owner;
        private final CloseOnceInputStream stdout;
        private final CloseOnceInputStream stderr;
        private final Consumer<Stream> pumpCloseObserver;
        private boolean stdoutPumpClosed;
        private boolean stderrPumpClosed;

        private Reservation(
                OutputCloseReservation owner,
                CloseOnceInputStream stdout,
                CloseOnceInputStream stderr,
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
            CloseOnceInputStream input = input(stream);
            input.dispatchReservedClose(this, threadPrefix, failureHandler, completionHandler);
        }

        void dispatchPair(
                String stdoutThreadPrefix,
                java.util.function.Consumer<? super Throwable> stdoutFailureHandler,
                Runnable stdoutCompletionHandler,
                String stderrThreadPrefix,
                java.util.function.Consumer<? super Throwable> stderrFailureHandler,
                Runnable stderrCompletionHandler) {
            stdout.dispatchReservedPair(
                    this,
                    stdoutThreadPrefix,
                    stdoutFailureHandler,
                    stdoutCompletionHandler,
                    stderr,
                    stderrThreadPrefix,
                    stderrFailureHandler,
                    stderrCompletionHandler);
        }

        private boolean markPumpClosed(Stream stream) {
            return switch (stream) {
                case STDOUT -> {
                    if (stdoutPumpClosed) {
                        yield false;
                    }
                    stdoutPumpClosed = true;
                    yield true;
                }
                case STDERR -> {
                    if (stderrPumpClosed) {
                        yield false;
                    }
                    stderrPumpClosed = true;
                    yield true;
                }
            };
        }

        boolean pumpClosed(Stream stream) {
            synchronized (owner.lock) {
                if (owner.reservation != this) {
                    throw new IllegalStateException("Process output close token is not active");
                }
                return switch (stream) {
                    case STDOUT -> stdoutPumpClosed;
                    case STDERR -> stderrPumpClosed;
                };
            }
        }

        void requireStream(Stream stream, CloseOnceInputStream input) {
            CloseOnceInputStream expected = input(stream);
            if (input != expected) {
                throw new IllegalStateException("Process output close token does not own " + stream);
            }
        }

        private CloseOnceInputStream input(Stream stream) {
            return switch (stream) {
                case STDOUT -> stdout;
                case STDERR -> stderr;
            };
        }
    }
}
