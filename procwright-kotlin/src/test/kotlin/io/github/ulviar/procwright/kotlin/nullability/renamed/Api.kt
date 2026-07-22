/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin.nullability.renamed

fun <T : Any> Map<String, List<T>>.transform(
    renamedInput: Map<String, List<T>>
): Map<String, List<T>> = renamedInput
