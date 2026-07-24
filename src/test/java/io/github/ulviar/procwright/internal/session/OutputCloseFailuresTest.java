/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class OutputCloseFailuresTest {

    @Test
    void primaryInstalledAfterFinalizationOwnsFutureCleanupFailures() {
        OutputCloseFailures failures = new OutputCloseFailures();
        AssertionError primary = new AssertionError("late primary");
        AssertionError stdoutFailure = new AssertionError("stdout close failed");
        AssertionError stderrFailure = new AssertionError("stderr close failed");

        failures.finish();
        failures.retainPrimary(primary);
        failures.record(stdoutFailure);
        failures.record(stderrFailure);

        assertEquals(2, primary.getSuppressed().length);
        assertEquals(stdoutFailure, primary.getSuppressed()[0]);
        assertEquals(stderrFailure, primary.getSuppressed()[1]);
    }
}
