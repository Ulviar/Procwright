/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

@FunctionalInterface
interface ExpectLateFatalFailureReporter {

    void report(Thread thread, Error failure);
}
