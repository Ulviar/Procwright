/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.ProcessIoResources;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the single physical close attempt for one process output stream. */
final class CloseOnceInputStream extends FilterInputStream {

    private static final Runnable NOOP_CLOSE_CLAIM_PROBE = () -> {};

    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final Runnable beforeCloseClaim;
    private final ProcessIoResources.Resource<InputStream> resource;
    private final OutputCloseReservation reservation;
    private final OutputCloseReservation.Stream stream;

    CloseOnceInputStream(InputStream delegate) {
        this(delegate, NOOP_CLOSE_CLAIM_PROBE);
    }

    CloseOnceInputStream(InputStream delegate, Runnable beforeCloseClaim) {
        this(delegate, beforeCloseClaim, null, null, null);
    }

    CloseOnceInputStream(
            InputStream delegate, OutputCloseReservation reservation, OutputCloseReservation.Stream stream) {
        this(delegate, NOOP_CLOSE_CLAIM_PROBE, null, reservation, stream);
    }

    CloseOnceInputStream(
            ProcessIoResources.Resource<InputStream> resource,
            OutputCloseReservation reservation,
            OutputCloseReservation.Stream stream) {
        this(resource.stream(), NOOP_CLOSE_CLAIM_PROBE, resource, reservation, stream);
    }

    CloseOnceInputStream(
            ProcessIoResources.Resource<InputStream> resource,
            Runnable beforeCloseClaim,
            OutputCloseReservation reservation,
            OutputCloseReservation.Stream stream) {
        this(resource.stream(), beforeCloseClaim, resource, reservation, stream);
    }

    CloseOnceInputStream(
            InputStream delegate,
            Runnable beforeCloseClaim,
            ProcessIoResources.Resource<InputStream> resource,
            OutputCloseReservation reservation,
            OutputCloseReservation.Stream stream) {
        super(Objects.requireNonNull(delegate, "delegate"));
        this.beforeCloseClaim = Objects.requireNonNull(beforeCloseClaim, "beforeCloseClaim");
        this.resource = resource;
        if ((reservation == null) != (stream == null)) {
            throw new IllegalArgumentException("reservation and stream must either both be set or both be absent");
        }
        this.reservation = reservation;
        this.stream = stream;
    }

    @Override
    public void close() throws IOException {
        if (closeStarted.get()) {
            return;
        }
        beforeCloseClaim.run();
        boolean claimed = reservation == null ? claimPhysicalClose() : reservation.claimOrdinaryClose(stream, this);
        if (claimed) {
            closePhysicalInline();
        }
    }

    boolean closeStarted() {
        return resource == null ? closeStarted.get() : resource.closeStarted();
    }

    boolean belongsTo(OutputCloseReservation expectedReservation, OutputCloseReservation.Stream expectedStream) {
        return reservation == expectedReservation && stream == expectedStream;
    }

    boolean claimPhysicalClose() {
        return resource != null || closeStarted.compareAndSet(false, true);
    }

    void dispatchReservedClose(
            OutputCloseReservation.Reservation closeReservation,
            String threadPrefix,
            java.util.function.Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {
        closeReservation.requireStream(stream, this);
        if (resource == null) {
            throw new IllegalStateException("Reserved asynchronous close requires process resource ownership");
        }
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
        if (resource == null || second.resource == null) {
            throw new IllegalStateException("Reserved paired close requires process resource ownership");
        }
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
        if (resource == null) {
            throw new IllegalStateException("Lifecycle asynchronous close requires process resource ownership");
        }
        resource.closeOwnedAsync(threadPrefix, failureHandler, completionHandler);
    }

    private void closePhysicalInline() throws IOException {
        if (resource == null) {
            super.close();
        } else {
            resource.closeInline();
        }
    }
}
