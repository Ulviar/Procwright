/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration.nullness

import io.github.ulviar.procwright.integration.CommandBackedTool
import io.github.ulviar.procwright.integration.JsonValue
import io.github.ulviar.procwright.integration.ProtocolAdapters
import io.github.ulviar.procwright.integration.ToolCallResult
import io.github.ulviar.procwright.session.ProtocolAdapter
import java.util.function.Supplier

fun validIntegrationNullness(
    tool: CommandBackedTool<String, JsonValue>,
    members: Map<String, JsonValue>,
    values: List<JsonValue>,
): Map<String, JsonValue> {
    val result: ToolCallResult<JsonValue> = tool.call("request")
    val jsonObject: JsonValue.JsonObject = JsonValue.`object`(members)
    val jsonArray: JsonValue.JsonArray = JsonValue.array(values)
    val nestedMembers: Map<String, JsonValue> = jsonObject.members()
    val nestedValues: List<JsonValue> = jsonArray.values()
    val adapterFactory: Supplier<ProtocolAdapter<String, JsonValue>> =
        ProtocolAdapters.typedJsonSession(
            { JsonValue.string(it) },
            { it },
            ProtocolAdapters.jsonLinesSession(256),
        )
    @Suppress("UNUSED_VARIABLE")
    val adapter: ProtocolAdapter<String, JsonValue> = adapterFactory.get()
    check(result.value().isPresent || result.error().isPresent)
    check(nestedValues.size <= values.size)
    return nestedMembers
}
