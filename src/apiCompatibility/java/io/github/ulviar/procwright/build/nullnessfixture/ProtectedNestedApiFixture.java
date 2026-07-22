/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build.nullnessfixture;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class ProtectedNestedApiFixture {

    protected static class ProtectedApi {

        protected @Nullable String value;
    }

    protected static class ProtectedApiNullabilityHostile {

        protected @NonNull String value;
    }

    private static class HiddenApi {}
}
