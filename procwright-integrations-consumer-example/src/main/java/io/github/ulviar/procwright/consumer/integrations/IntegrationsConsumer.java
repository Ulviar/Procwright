/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.consumer.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.ProtocolSessionScenario;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.integration.JsonCodec;
import io.github.ulviar.procwright.integration.JsonValue;
import io.github.ulviar.procwright.integration.ProtocolAdapters;

final class IntegrationsConsumer {

    private IntegrationsConsumer() {}

    static CommandService command(String executable) {
        return Procwright.command(CommandSpec.of(executable));
    }

    static JsonNode jacksonValue() {
        return JsonCodec.toJackson(JsonValue.bool(true));
    }

    static ProtocolSessionScenario.Draft<JsonValue, JsonValue> jsonLinesSession(String executable) {
        return command(executable).protocolSession(ProtocolAdapters.jsonLinesSession(1024));
    }

    static ProtocolSessionScenario.PoolDraft<JsonValue, JsonValue> jsonLinesPool(String executable) {
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

    static ProtocolSessionScenario.Draft<JsonValue, JsonValue> contentLengthSession(String executable) {
        return command(executable).protocolSession(ProtocolAdapters.contentLengthJsonSession(1024));
    }

    static ProtocolSessionScenario.PoolDraft<JsonValue, JsonValue> contentLengthPool(String executable) {
        return command(executable)
                .protocolSession(ProtocolAdapters.contentLengthJsonSession(1024))
                .pooled();
    }

    static ProtocolSessionScenario.Draft<String, String> typedJsonSession(String executable) {
        return command(executable)
                .protocolSession(ProtocolAdapters.typedJsonSession(
                        JsonValue::string, IntegrationsConsumer::stringValue, ProtocolAdapters.jsonLinesSession(1024)));
    }

    static ProtocolSessionScenario.PoolDraft<String, String> typedJsonPool(String executable) {
        return command(executable)
                .protocolSession(ProtocolAdapters.typedJsonSession(
                        JsonValue::string,
                        IntegrationsConsumer::stringValue,
                        ProtocolAdapters.contentLengthJsonSession(1024)))
                .pooled();
    }

    private static String stringValue(JsonValue value) {
        return ((JsonValue.JsonString) value).value();
    }
}
