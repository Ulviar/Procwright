/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

/** Dispatches a potentially blocking JDK {@link Process} destroy fallback within bounded shared capacity. */
@FunctionalInterface
interface DestroyFallbackDispatcher {

    void dispatch(String threadPrefix, Runnable action);
}
