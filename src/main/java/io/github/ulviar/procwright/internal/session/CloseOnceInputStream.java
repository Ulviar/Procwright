/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.ProcessIoResources;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Owns the single physical close attempt for one process output stream. */
final class CloseOnceInputStream extends FilterInputStream {

    private final ProcessIoResources.Resource<InputStream> resource;
    private final OutputCloseReservation reservation;
    private final OutputCloseReservation.Stream stream;

    CloseOnceInputStream(
            ProcessIoResources.Resource<InputStream> resource,
            OutputCloseReservation reservation,
            OutputCloseReservation.Stream stream) {
        super(Objects.requireNonNull(resource, "resource").stream());
        this.resource = resource;
        this.reservation = Objects.requireNonNull(reservation, "reservation");
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    @Override
    public void close() throws IOException {
        if (reservation.claimOrdinaryClose(stream, this)) {
            resource.closeInline();
        }
    }

    boolean closeStarted() {
        return resource.closeStarted();
    }

    boolean belongsTo(OutputCloseReservation expectedReservation, OutputCloseReservation.Stream expectedStream) {
        return reservation == expectedReservation && stream == expectedStream;
    }

    void dispatchReservedClose(
            OutputCloseReservation.Reservation closeReservation,
            String threadPrefix,
            java.util.function.Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {
        closeReservation.requireStream(stream, this);
        resource.closeOwnedAsync(threadPrefix, failureHandler, completionHandler);
    }

    void dispatchReservedPair(
            OutputCloseReservation.Reservation closeReservation,
            String threadPrefix,
            java.util.function.Consumer<? super Throwable> failureHandler,
            Runnable completionHandler,
            CloseOnceInputStream second,
            String secondThreadPrefix,
            java.util.function.Consumer<? super Throwable> secondFailureHandler,
            Runnable secondCompletionHandler) {
        closeReservation.requireStream(stream, this);
        closeReservation.requireStream(second.stream, second);
        ProcessIoResources.closePairAsync(
                resource,
                threadPrefix,
                failureHandler,
                completionHandler,
                second.resource,
                secondThreadPrefix,
                secondFailureHandler,
                secondCompletionHandler);
    }

    void dispatchLifecycleClose(
            String threadPrefix,
            java.util.function.Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {
        resource.closeOwnedAsync(threadPrefix, failureHandler, completionHandler);
    }
}
