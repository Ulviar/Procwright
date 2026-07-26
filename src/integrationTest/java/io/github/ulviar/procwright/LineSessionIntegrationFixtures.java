/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.session.LineSession;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

final class LineSessionIntegrationFixtures {

    private LineSessionIntegrationFixtures() {}

    static LineSessionScenario.Draft fixtureScenario() {
        return Procwright.command(TestCliSupport.command()).lineSession();
    }

    static void sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        }
    }

    static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static LineSession openLineSession(
            LineSessionScenario.Draft scenario, UnaryOperator<LineSessionScenario.Draft> configure) {
        return configure.apply(scenario).open();
    }
}
