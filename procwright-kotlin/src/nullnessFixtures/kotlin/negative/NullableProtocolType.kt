/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin.nullness

import io.github.ulviar.procwright.session.ProtocolAdapter

fun nullableProtocolType(
    adapter: ProtocolAdapter<String?, String?>
): ProtocolAdapter<String?, String?> = adapter
