/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.PooledLineSession;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

final class PooledLineSessionIntegrationFixtures {
    private PooledLineSessionIntegrationFixtures() {}

    static LineSessionScenario.Draft fixtureScenario() {
        return Procwright.command(TestCliSupport.command()).lineSession();
    }

    static LineSessionScenario.PoolDraft poolDraft(LineSessionScenario.Draft scenario, String... workerArguments) {
        return scenario.withArgs(workerArguments).pooled();
    }

    static boolean awaitLeased(PooledLineSession pool, int expectedLeased) throws InterruptedException {
        return PoolTestAccess.awaitLineMetrics(
                pool, metrics -> metrics.leased() == expectedLeased, Duration.ofSeconds(2));
    }

    static boolean awaitRetired(PooledLineSession pool, long expectedRetired) throws InterruptedException {
        return PoolTestAccess.awaitLineMetrics(
                pool, metrics -> metrics.retired() == expectedRetired, Duration.ofSeconds(2));
    }

    static void awaitIgnoringInterrupt(CountDownLatch latch) {
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
}
