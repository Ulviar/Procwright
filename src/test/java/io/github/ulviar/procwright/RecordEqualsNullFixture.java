/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.terminal.TerminalSize;

final class RecordEqualsNullFixture {

    private RecordEqualsNullFixture() {}

    static boolean generatedEqualsNull() {
        return TerminalSize.defaults().equals(null);
    }
}
