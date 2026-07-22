/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration.nullness

import io.github.ulviar.procwright.integration.CommandBackedTool

class NullableIntegrationReturn : CommandBackedTool.Handler<String, String> {
    override fun handle(input: String): String? = null
}
