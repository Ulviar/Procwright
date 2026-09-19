/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.integration;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.ProtocolSessionScenario;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.integration.ProtocolAdapters;
import io.github.ulviar.procwright.session.ProtocolSession;
import tools.jackson.databind.node.JsonNodeFactory;

public final class TextWorkerService implements AutoCloseable {

    public record Request(String text) {}

    public record Metrics(int codePoints, int utf8Bytes) {}

    // docs:start service
    private final ProtocolSession<Request, Metrics> session;

    public TextWorkerService(CommandSpec command) {
        session = draft(command).open();
    }

    public Metrics analyze(String text) {
        return session.request(new Request(text));
    }

    @Override
    public void close() {
        session.close();
    }
    // docs:end service

    // docs:start protocol
    public static ProtocolSessionScenario.Draft<Request, Metrics> draft(CommandSpec command) {
        var adapters = ProtocolAdapters.typedJson(
                (Request request) -> JsonNodeFactory.instance.objectNode().put("text", request.text()),
                response -> new Metrics(
                        response.required("codePoints").intValue(),
                        response.required("utf8Bytes").intValue()),
                ProtocolAdapters.jsonLines(64 * 1024));
        return Procwright.command(command).protocolSession(adapters);
    }
    // docs:end protocol
}
