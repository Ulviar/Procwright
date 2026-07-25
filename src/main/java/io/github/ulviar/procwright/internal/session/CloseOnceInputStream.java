/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.ProcessStreamResource;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Owns the single physical close attempt for one process output stream. */
final class CloseOnceInputStream extends FilterInputStream {

    private final ProcessStreamResource<InputStream> resource;
    private final OutputCloseReservation reservation;
    private final OutputCloseReservation.Stream stream;

    CloseOnceInputStream(
            ProcessStreamResource<InputStream> resource,
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

    ProcessStreamResource<InputStream> resource() {
        return resource;
    }
}
