/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.consumer.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.ProtocolSessionScenario;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.integration.ProtocolAdapters;

final class IntegrationsConsumer {

    private IntegrationsConsumer() {}

    static CommandService command(String executable) {
        return Procwright.command(CommandSpec.of(executable));
    }

    static JsonNode jacksonValue() {
        return BooleanNode.TRUE;
    }

    static ProtocolSessionScenario.Draft<JsonNode, JsonNode> jsonLinesSession(String executable) {
        return command(executable).protocolSession(ProtocolAdapters.jsonLinesSession(1024));
    }

    static ProtocolSessionScenario.PoolDraft<JsonNode, JsonNode> jsonLinesPool(String executable) {
        return command(executable)
                .protocolSession(ProtocolAdapters.jsonLinesSession(1024))
                .pooled();
    }

    static ProtocolSessionScenario.Draft<byte[], byte[]> delimiterSession(String executable) {
        return command(executable).protocolSession(ProtocolAdapters.delimiterSession((byte) 0, 1024));
    }

    static ProtocolSessionScenario.PoolDraft<byte[], byte[]> delimiterPool(String executable) {
        return command(executable)
                .protocolSession(ProtocolAdapters.delimiterSession((byte) 0, 1024))
                .pooled();
    }

    static ProtocolSessionScenario.Draft<JsonNode, JsonNode> contentLengthSession(String executable) {
        return command(executable).protocolSession(ProtocolAdapters.contentLengthJsonSession(1024));
    }

    static ProtocolSessionScenario.PoolDraft<JsonNode, JsonNode> contentLengthPool(String executable) {
        return command(executable)
                .protocolSession(ProtocolAdapters.contentLengthJsonSession(1024))
                .pooled();
    }

    static ProtocolSessionScenario.Draft<String, String> typedJsonSession(String executable) {
        return command(executable)
                .protocolSession(ProtocolAdapters.typedJsonSession(
                        TextNode::valueOf, JsonNode::textValue, ProtocolAdapters.jsonLinesSession(1024)));
    }

    static ProtocolSessionScenario.PoolDraft<String, String> typedJsonPool(String executable) {
        return command(executable)
                .protocolSession(ProtocolAdapters.typedJsonSession(
                        TextNode::valueOf, JsonNode::textValue, ProtocolAdapters.contentLengthJsonSession(1024)))
                .pooled();
    }
}
