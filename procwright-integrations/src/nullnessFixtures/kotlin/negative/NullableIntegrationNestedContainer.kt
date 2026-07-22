/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration.nullness

import io.github.ulviar.procwright.integration.JsonValue

fun nullableIntegrationNestedContainer(values: Map<String, JsonValue?>) {
    JsonValue.`object`(values)
}
