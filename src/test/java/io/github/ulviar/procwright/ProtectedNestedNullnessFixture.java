/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import org.jspecify.annotations.Nullable;

public class ProtectedNestedNullnessFixture {

    protected static class ProtectedApi {

        protected @Nullable String value;
    }

    private static class HiddenApi {}
}
