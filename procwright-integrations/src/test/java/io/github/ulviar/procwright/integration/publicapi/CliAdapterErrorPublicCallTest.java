/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration.publicapi;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.integration.CliAdapterError;
import io.github.ulviar.procwright.integration.CommandBackedTool;
import org.junit.jupiter.api.Test;

final class CliAdapterErrorPublicCallTest {

    @Test
    void exhaustedRuntimeFailureIsRethrownUnchangedThroughPublicApi() {
        RuntimeException original = new IllegalArgumentException("controlled", causeChain(96));

        RuntimeException propagated = assertThrows(RuntimeException.class, () -> CliAdapterError.from(original));

        assertSame(original, propagated);
    }

    @Test
    void errorIsRethrownUnchangedThroughPublicApi() {
        AssertionError original = new AssertionError("controlled");

        AssertionError propagated = assertThrows(AssertionError.class, () -> CliAdapterError.from(original));

        assertSame(original, propagated);
    }

    @Test
    void exhaustedCheckedFailureIsWrappedAtPublicApiBoundary() {
        Exception original = new Exception("controlled", causeChain(96));

        IllegalStateException propagated =
                assertThrows(IllegalStateException.class, () -> CliAdapterError.from(original));

        assertSame(original, propagated.getCause());
    }

    @Test
    void commandBackedToolDoesNotLeakCheckedFailurePastPublicCallBoundary() {
        Exception original = new Exception("controlled", causeChain(96));
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw original;
        });

        IllegalStateException propagated = assertThrows(IllegalStateException.class, () -> tool.call("request"));

        assertSame(original, propagated.getCause());
    }

    private static Throwable causeChain(int length) {
        Throwable current = new IllegalStateException("terminal");
        for (int index = 0; index < length; index++) {
            current = new IllegalStateException("link", current);
        }
        return current;
    }
}
