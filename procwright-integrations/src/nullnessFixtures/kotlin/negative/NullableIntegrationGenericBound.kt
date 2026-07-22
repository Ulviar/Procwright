/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration.nullness

import io.github.ulviar.procwright.integration.CommandBackedTool

fun nullableIntegrationGenericBound(
    tool: CommandBackedTool<String?, String?>
): CommandBackedTool<String?, String?> = tool
