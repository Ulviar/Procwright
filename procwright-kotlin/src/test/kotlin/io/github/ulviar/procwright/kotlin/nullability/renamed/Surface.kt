/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin.nullability.renamed

typealias NestedAlias<T> = Map<String, List<T>>

class ConstructorAndPropertyFixture(renamedInitial: String) {
    var value: String = renamedInitial
        set(renamedIncoming) {
            field = renamedIncoming
        }

    fun varargValues(vararg renamedItems: String): List<String> = renamedItems.toList()
}

class GenericOuter<T : Any> {
    inner class Inner<U : Any>
}

fun starProjection(renamedValues: List<*>): List<*> = renamedValues

fun <T> definitelyNonNull(renamedValue: T & Any): T & Any = renamedValue

fun <T : Any, U : Any> GenericOuter<T>.Inner<U>.ownerAndAlias(
    renamedValue: NestedAlias<U>
): NestedAlias<U> = renamedValue
