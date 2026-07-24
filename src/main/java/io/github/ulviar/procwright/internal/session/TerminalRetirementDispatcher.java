/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/** Dispatches physical worker-close work while retaining that worker's lifecycle admission. */
@FunctionalInterface
interface TerminalRetirementDispatcher {

    PoolLifecycleDispatcher.Ownership dispatch(PoolLifecycleDispatcher.Admission admission, Runnable task);
}
