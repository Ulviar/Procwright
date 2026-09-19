/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.util.Objects;
import java.util.function.Consumer;

public final class BoundedCloseDispatcherTestAccess {

    private BoundedCloseDispatcherTestAccess() {}

    public static void dispatch(
            BoundedCloseDispatcher dispatcher,
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> failureHandler) {
        dispatch(dispatcher, request(closeable, threadPrefix, failureHandler));
    }

    public static void dispatch(BoundedCloseDispatcher dispatcher, BoundedCloseDispatcher.CloseRequest request) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(request, "request");
        dispatcher.dispatch(request);
    }

    public static void dispatchRequired(
            BoundedCloseDispatcher dispatcher,
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> failureHandler) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.dispatchRequired(request(closeable, threadPrefix, failureHandler));
    }

    public static BoundedCloseDispatcher.CloseRequest request(
            Closeable closeable, String threadPrefix, Consumer<? super Throwable> failureHandler) {
        return BoundedCloseDispatcher.ownedCloseRequest(closeable, threadPrefix, ignored -> {}, failureHandler);
    }
}
