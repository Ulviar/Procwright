/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.function.IntConsumer;

final class ProtocolOutputQueueTestAccess {

    private ProtocolOutputQueueTestAccess() {}

    static int read(
            ProtocolOutputQueue queue,
            byte[] buffer,
            int offset,
            int length,
            long deadlineNanos,
            ProtocolRuntimeFailures failures) {
        return queue.read(buffer, offset, length, deadlineNanos, failures, ignored -> {}, event -> event);
    }

    static int readUnsignedByte(ProtocolOutputQueue queue, long deadlineNanos, ProtocolRuntimeFailures failures) {
        return queue.readUnsignedByte(deadlineNanos, failures, ignored -> {}, event -> event);
    }

    static int readUnsignedByte(
            ProtocolOutputQueue queue,
            long deadlineNanos,
            ProtocolRuntimeFailures failures,
            IntConsumer beforeMutation) {
        return queue.readUnsignedByte(deadlineNanos, failures, beforeMutation, event -> event);
    }
}
