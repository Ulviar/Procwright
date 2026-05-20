package com.github.ulviar.icli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SessionContractShapeTest {

    @Test
    void runtimeHandleContractsAreSealedNonSpiTypes() {
        assertSealedWithSingleImplementation(Session.class, "com.github.ulviar.icli.internal.session.DefaultSession");
        assertSealedWithSingleImplementation(Expect.class, "com.github.ulviar.icli.internal.session.DefaultExpect");
        assertSealedWithSingleImplementation(
                LineSession.class, "com.github.ulviar.icli.internal.session.DefaultLineSession");
        assertSealedWithSingleImplementation(
                PooledLineSession.class, "com.github.ulviar.icli.internal.session.DefaultPooledLineSession");
        assertSealedWithSingleImplementation(
                ProtocolSession.class, "com.github.ulviar.icli.internal.session.DefaultProtocolSession");
        assertSealedWithSingleImplementation(
                PooledProtocolSession.class, "com.github.ulviar.icli.internal.session.DefaultPooledProtocolSession");
        assertSealedWithSingleImplementation(
                StreamSession.class, "com.github.ulviar.icli.internal.session.DefaultStreamSession");
    }

    private static void assertSealedWithSingleImplementation(Class<?> contract, String implementationName) {
        assertTrue(contract.isSealed(), () -> contract.getName() + " must be sealed");
        Class<?>[] permitted = contract.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        assertEquals(implementationName, permitted[0].getName());
    }
}
