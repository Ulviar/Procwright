/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin.nullness

import io.github.ulviar.procwright.RunScenario

fun nullableVarargElement(draft: RunScenario.Draft, arguments: Array<String?>) {
    draft.withArgs(*arguments)
}
