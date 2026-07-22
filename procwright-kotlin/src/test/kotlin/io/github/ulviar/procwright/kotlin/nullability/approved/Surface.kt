/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin.nullability.approved

typealias NestedAlias<T> = Map<String, List<T>>

class ConstructorAndPropertyFixture(initial: String) {
    var value: String = initial
        set(incoming) {
            field = incoming
        }

    fun varargValues(vararg items: String): List<String> = items.toList()
}

class GenericOuter<T : Any> {
    inner class Inner<U : Any>
}

fun starProjection(values: List<*>): List<*> = values

fun <T> definitelyNonNull(value: T & Any): T & Any = value

fun <T : Any, U : Any> GenericOuter<T>.Inner<U>.ownerAndAlias(
    value: NestedAlias<U>
): NestedAlias<U> = value
