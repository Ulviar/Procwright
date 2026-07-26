/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class StreamExitTest {

    @Test
    void rejectsContradictoryTerminationReasons() {
        var diagnostics = new StreamTranscript("", false);

        assertThrows(
                IllegalArgumentException.class,
                () -> new StreamExit(OptionalInt.empty(), true, true, diagnostics, Duration.ZERO));
    }
}
