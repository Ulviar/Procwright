/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.util.Objects;
import java.util.function.Consumer;

public final class BoundedCloseDispatcherTestAccess {

    private BoundedCloseDispatcherTestAccess() {}

    public static void dispatch(
            BoundedCloseDispatcher.Reservation reservation,
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {
        dispatch(reservation, request(closeable, threadPrefix, failureHandler, completionHandler));
    }

    public static void dispatch(
            BoundedCloseDispatcher.Reservation reservation,
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> failureHandler) {
        dispatch(reservation, request(closeable, threadPrefix, failureHandler));
    }

    public static void dispatch(
            BoundedCloseDispatcher.Reservation reservation, BoundedCloseDispatcher.CloseRequest request) {
        Objects.requireNonNull(request, "request");
        reservation.takePermit().dispatchOutcome(request).rethrowStartFailure();
    }

    public static void dispatchPair(
            BoundedCloseDispatcher.Permit firstPermit,
            BoundedCloseDispatcher.CloseRequest first,
            BoundedCloseDispatcher.Permit secondPermit,
            BoundedCloseDispatcher.CloseRequest second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        firstPermit.dispatchPairOutcome(first, secondPermit, second).rethrowStartFailure();
    }

    public static BoundedCloseDispatcher.CloseRequest request(
            Closeable closeable, String threadPrefix, Consumer<? super Throwable> failureHandler) {
        return request(closeable, threadPrefix, failureHandler, () -> {});
    }

    public static BoundedCloseDispatcher.CloseRequest request(
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {
        return new BoundedCloseDispatcher.CloseRequest(
                closeable, threadPrefix, ignored -> {}, failureHandler, completionHandler);
    }
}
