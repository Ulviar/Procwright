/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SessionContractShapeTest {

    @Test
    void runtimeHandleContractsAreSealedNonSpiTypes() {
        assertSealedWithSingleImplementation(
                Session.class, "io.github.ulviar.procwright.internal.session.DefaultSession");
        assertSealedWithSingleImplementation(
                Expect.class, "io.github.ulviar.procwright.internal.session.DefaultExpect");
        assertSealedWithSingleImplementation(
                LineSession.class, "io.github.ulviar.procwright.internal.session.DefaultLineSession");
        assertSealedWithSingleImplementation(
                PooledLineSession.class, "io.github.ulviar.procwright.internal.session.DefaultPooledLineSession");
        assertSealedWithSingleImplementation(
                ProtocolSession.class, "io.github.ulviar.procwright.internal.session.DefaultProtocolSession");
        assertSealedWithSingleImplementation(
                PooledProtocolSession.class,
                "io.github.ulviar.procwright.internal.session.DefaultPooledProtocolSession");
        assertSealedWithSingleImplementation(
                StreamSession.class, "io.github.ulviar.procwright.internal.session.DefaultStreamSession");
    }

    private static void assertSealedWithSingleImplementation(Class<?> contract, String implementationName) {
        assertTrue(contract.isSealed(), () -> contract.getName() + " must be sealed");
        Class<?>[] permitted = contract.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        assertEquals(implementationName, permitted[0].getName());
    }
}
