/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.assertStdoutEquals;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.normalizeLineEndings;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class RunTimeoutIntegrationTest {

    @Test
    void hugeTimeoutIsSaturatedInsteadOfOverflowing() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("exit", "--stdout=ok\n")
                .withTimeout(Duration.ofSeconds(Long.MAX_VALUE))
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("ok\n", result);
    }

    @Test
    void zeroTimeoutDisablesRunTimeoutAndAwaitsCompletion() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("sleep", "--millis=300", "--finished=true")
                .withTimeout(Duration.ZERO)
                .execute();

        assertTrue(result.succeeded());
        assertFalse(result.timedOut());
        assertTrue(normalizeLineEndings(result.stdout()).contains("finished\n"));
    }

    @Test
    void negativeTimeoutIsRejectedBeforeLaunch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> fixtureService()
                        .run()
                        .withArgs("exit")
                        .withTimeout(Duration.ofMillis(-1))
                        .execute());
    }
}
