/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ProtocolAdapterFactoryTest {

    @Test
    void adapterCreationPreservesFactoryFailuresAndRejectsNullResults() {
        RuntimeException runtimeFailure = new IllegalStateException("factory failure");
        assertSame(
                runtimeFailure,
                assertThrows(
                        RuntimeException.class,
                        () -> ScenarioRuntime.createProtocolAdapter(() -> {
                            throw runtimeFailure;
                        })));

        AssertionError error = new AssertionError("factory error");
        assertSame(
                error,
                assertThrows(
                        AssertionError.class,
                        () -> ScenarioRuntime.createProtocolAdapter(() -> {
                            throw error;
                        })));

        NullPointerException nullFactory =
                assertThrows(NullPointerException.class, () -> ScenarioRuntime.createProtocolAdapter(null));
        NullPointerException nullResult =
                assertThrows(NullPointerException.class, () -> ScenarioRuntime.createProtocolAdapter(() -> null));
        assertEquals("adapterFactory", nullFactory.getMessage());
        assertEquals("adapterFactory returned null", nullResult.getMessage());
    }
}
