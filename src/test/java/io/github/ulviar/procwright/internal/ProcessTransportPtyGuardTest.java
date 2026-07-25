/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.ProcessTreeScannerStubs.StubProcess;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProcessTransportPtyGuardTest {

    @Test
    void successfulCustomPtyProviderProcessIsGuardedBeforeRuntimePublication() {
        Process supplied = new StubProcess();
        PtyProvider provider = new PtyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "test provider";
            }

            @Override
            public Process start(PtyRequest request) {
                return supplied;
            }
        };

        Process started = ProcessTransport.start(sessionPlan(provider));

        assertTrue(started instanceof GuardedProcess);
        assertSame(supplied, ((GuardedProcess) started).delegate());
    }

    @Test
    void customPtyProviderProcessCannotCarryThreadLocalStateIntoALaterOperation() {
        ThreadLocal<Object> providerState = new ThreadLocal<>();
        Object retainedUserGraph = List.of(new byte[1_024]);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        Process supplied = new StubProcess() {
            @Override
            public boolean isAlive() {
                if (calls.getAndIncrement() == 0) {
                    assertNull(providerState.get());
                    firstWorker.set(Thread.currentThread());
                    providerState.set(retainedUserGraph);
                } else {
                    assertNotSame(firstWorker.get(), Thread.currentThread());
                    assertNull(providerState.get());
                }
                return true;
            }
        };
        PtyProvider provider = new PtyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "thread-local contamination provider";
            }

            @Override
            public Process start(PtyRequest request) {
                return supplied;
            }
        };
        SessionExecutionPlan plan = sessionPlan(provider);
        Process started = ProcessTransport.start(plan);

        assertTrue(started.isAlive());
        assertTrue(started.isAlive());
        assertEquals(2, calls.get());
    }

    private static SessionExecutionPlan sessionPlan(PtyProvider provider) {
        LaunchPlan launch = new LaunchPlan(
                List.of("test"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.REQUIRED);
        return new SessionExecutionPlan(
                launch,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                provider,
                TerminalSize.defaults());
    }
}
