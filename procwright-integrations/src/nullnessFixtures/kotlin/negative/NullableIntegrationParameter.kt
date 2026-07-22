/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration.nullness

import io.github.ulviar.procwright.integration.CommandBackedTool

fun nullableIntegrationParameter(tool: CommandBackedTool<String, String>) {
    tool.call(null)
}
