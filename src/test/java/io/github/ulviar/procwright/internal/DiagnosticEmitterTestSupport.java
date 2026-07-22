/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiagnosticEmitterTestSupport {

    private DiagnosticEmitterTestSupport() {}

    public static DiagnosticEmitter failOnceOn(
            DiagnosticsSettings settings, String scenario, DiagnosticEventType failingType, Error diagnosticFailure) {
        AtomicBoolean failed = new AtomicBoolean();
        return DiagnosticEmitter.of(
                settings,
                scenario,
                CommandEcho.empty(),
                (type, runId, timestamp, eventScenario, command, attributes) -> {
                    if (type == failingType && failed.compareAndSet(false, true)) {
                        throw diagnosticFailure;
                    }
                    return new DiagnosticEvent(type, runId, timestamp, eventScenario, command, attributes);
                });
    }

    public static DiagnosticEmitter blockOnceOn(
            DiagnosticsSettings settings,
            String scenario,
            DiagnosticEventType blockedType,
            CountDownLatch entered,
            CountDownLatch release) {
        AtomicBoolean blocked = new AtomicBoolean();
        return DiagnosticEmitter.of(
                settings,
                scenario,
                CommandEcho.empty(),
                (type, runId, timestamp, eventScenario, command, attributes) -> {
                    if (type == blockedType && blocked.compareAndSet(false, true)) {
                        entered.countDown();
                        awaitUninterruptibly(release);
                    }
                    return new DiagnosticEvent(type, runId, timestamp, eventScenario, command, attributes);
                });
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean restoreInterrupt = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }
}
