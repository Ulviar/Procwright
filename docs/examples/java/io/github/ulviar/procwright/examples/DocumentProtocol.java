/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

public final class DocumentProtocol {

    private DocumentProtocol() {}

    public record DocumentRequest(String text) {}

    public record DocumentResponse(String text) {}
}
