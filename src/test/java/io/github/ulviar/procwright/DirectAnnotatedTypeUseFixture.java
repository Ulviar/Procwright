/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import org.jspecify.annotations.Nullable;

class DirectAnnotatedTypeUseFixture extends @Nullable DirectNullableBase implements @Nullable DirectNullableContract {

    protected void traverse(@Nullable DirectAnnotatedTypeUseFixture this) throws @Nullable DirectNullableFailure {}
}

class DirectNullableBase {}

interface DirectNullableContract {}

class DirectNullableFailure extends Exception {

    private static final long serialVersionUID = 1L;
}
