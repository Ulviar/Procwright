/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

enum NoopDiagnosticListener implements DiagnosticListener {
    INSTANCE;

    @Override
    public void onEvent(DiagnosticEvent event) {
        // Intentionally ignored.
    }
}
