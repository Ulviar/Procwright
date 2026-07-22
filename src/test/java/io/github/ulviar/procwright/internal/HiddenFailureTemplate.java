/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

final class HiddenFailureTemplate extends AssertionError {

    private static final long serialVersionUID = 1L;

    HiddenFailureTemplate() {
        super("hidden failure");
    }
}
