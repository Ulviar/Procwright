/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin.nullability.approved

fun <T : Any> Map<String, List<T>>.transform(input: Map<String, List<T>>): Map<String, List<T>> =
    input
