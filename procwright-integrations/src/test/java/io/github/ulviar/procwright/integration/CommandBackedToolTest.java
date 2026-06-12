/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CommandBackedToolTest {

    @Test
    void mapsThrowingHandlerToStructuredFailure() {
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw new JsonParseException("bad json");
        });

        ToolCallResult<String> result = tool.call("payload");

        assertFalse(result.succeeded());
        assertEquals("protocol_error", result.error().orElseThrow().code());
    }

    @Test
    void returnsSuccessValue() {
        CommandBackedTool<String, Integer> tool = CommandBackedTool.of(String::length);

        ToolCallResult<Integer> result = tool.call("tool");

        assertTrue(result.succeeded());
        assertEquals(4, result.value().orElseThrow());
    }

    @Test
    void doesNotConvertSeriousErrorsToToolFailures() {
        CommandBackedTool<String, String> tool = CommandBackedTool.of(input -> {
            throw new AssertionError("serious");
        });

        assertThrows(AssertionError.class, () -> tool.call("payload"));
    }

    @Test
    void cancellableCallRunsCancelActionOnce() {
        AtomicInteger cancels = new AtomicInteger();
        java.util.concurrent.CompletableFuture<String> completion = new java.util.concurrent.CompletableFuture<>();
        CancellableCall<String> call = new CancellableCall<>(completion, cancels::incrementAndGet);

        assertTrue(call.cancel());
        assertFalse(call.cancel());

        assertEquals(1, cancels.get());
        assertTrue(call.cancellationRequested());
        assertTrue(completion.isCancelled());
    }

    @Test
    void lateCancellationDoesNotRunCancelAction() {
        AtomicInteger cancels = new AtomicInteger();
        java.util.concurrent.CompletableFuture<String> completion =
                java.util.concurrent.CompletableFuture.completedFuture("done");
        CancellableCall<String> call = new CancellableCall<>(completion, cancels::incrementAndGet);

        assertFalse(call.cancel());

        assertEquals(0, cancels.get());
        assertFalse(call.cancellationRequested());
    }
}
