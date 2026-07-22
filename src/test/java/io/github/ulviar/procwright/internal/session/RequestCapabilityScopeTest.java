/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RequestCapabilityScopeTest {

    @Test
    void scopeExpiresOnItsOwningThreadAndCannotBeReactivated() {
        RequestCapabilityScope scope = new RequestCapabilityScope("test capability");
        scope.activate();
        scope.verifyAccess();

        scope.invalidate();

        assertThrows(IllegalStateException.class, scope::verifyAccess);
        assertThrows(IllegalStateException.class, scope::activate);
    }

    @Test
    void activeScopeRejectsAnotherThreadWithoutInvalidatingItsOwner() throws Exception {
        RequestCapabilityScope scope = new RequestCapabilityScope("test capability");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        scope.activate();
        Thread foreign = new Thread(
                () -> observed.set(captureFailure(scope::verifyAccess)), "procwright-request-capability-scope-test");
        foreign.setDaemon(true);

        foreign.start();
        foreign.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(foreign.isAlive());
        assertInstanceOf(IllegalStateException.class, observed.get());
        scope.verifyAccess();
    }

    @Test
    void newRequestUsesANewGenerationInsteadOfReactivatingAnExpiredScope() {
        RequestCapabilityScope expired = new RequestCapabilityScope("test capability");
        expired.activate();
        expired.invalidate();
        RequestCapabilityScope nextRequest = new RequestCapabilityScope("test capability");

        nextRequest.activate();

        assertThrows(IllegalStateException.class, expired::verifyAccess);
        nextRequest.verifyAccess();
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}
