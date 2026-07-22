/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build.nullnessfixture;

import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@NullUnmarked
public final class NullnessSignatureFixture<T extends @Nullable Object> extends @Nullable NullableBase
        implements @Nullable NullableContract {

    public List<? extends @Nullable String @Nullable []> nested;

    public @Nullable Owner<String>.Nested<Integer> annotatedOwner;

    @SuppressWarnings("rawtypes")
    public @Nullable Owner.RawNested rawAnnotatedOwner;

    protected @Nullable T protectedValue;

    @NullMarked
    public NullnessSignatureFixture(@Nullable T value) {
        protectedValue = value;
    }

    @NullUnmarked
    public <U extends @Nullable Object> @Nullable U transform(
            @Nullable NullnessSignatureFixture<T> this,
            List<? super @Nullable T> values,
            @Nullable String @Nullable [] input)
            throws @Nullable IOException {
        return null;
    }

    public static final class Owner<O> {

        public final class Nested<N> {}

        public final class RawNested {}
    }
}

class NullableBase {}

interface NullableContract {}
