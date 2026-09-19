/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

/** Owns callback-confined capability, deadline, terminal precedence, and raw-byte access for one protocol reader. */
final class ProtocolReadSource {

    private final ProtocolOutputQueue output;
    private final long deadlineNanos;
    private final ProtocolResponseBudget budget;
    private final ProtocolRuntimeFailures failures;
    private final RequestCapabilityScope capabilityScope;
    private final ProtocolOutputQueue.ReadWindow readWindow = new ProtocolOutputQueue.ReadWindow();
    private final IntConsumer countBytes;
    private final UnaryOperator<ProtocolOutputEvent> terminalObserver = this::claimTerminal;
    private boolean readStarted;
    private ProtocolOutputEvent claimedTerminal;

    ProtocolReadSource(
            ProtocolOutputQueue output,
            long deadlineNanos,
            ProtocolResponseBudget budget,
            ProtocolRuntimeFailures failures,
            RequestCapabilityScope capabilityScope) {
        this.output = Objects.requireNonNull(output, "output");
        this.deadlineNanos = deadlineNanos;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.countBytes = budget::addBytes;
        this.failures = Objects.requireNonNull(failures, "failures");
        this.capabilityScope = Objects.requireNonNull(capabilityScope, "capabilityScope");
    }

    int readOneUnsignedByte() {
        checkReadPreconditions();
        budget.ensureBytesAvailable(1);
        return output.readUnsignedByte(readWindow, deadlineNanos, failures, countBytes, terminalObserver);
    }

    int readAvailableBytes(byte[] buffer, int offset, int length) {
        checkReadPreconditions();
        budget.ensureBytesAvailable(1);
        return output.read(buffer, offset, length, readWindow, deadlineNanos, failures, countBytes, terminalObserver);
    }

    void checkReadPreconditions() {
        capabilityScope.verifyAccess();
        ProtocolOutputEvent terminal = claimInitialTerminal();
        if (terminal != null) {
            throw terminal.terminalFailure(failures);
        }
        checkDeadline();
    }

    void checkLineReadPreconditions(boolean pendingLineOutput) {
        capabilityScope.verifyAccess();
        if (!pendingLineOutput) {
            ProtocolOutputEvent terminal = claimInitialTerminal();
            if (terminal != null) {
                throw terminal.terminalFailure(failures);
            }
        }
        checkDeadline();
    }

    void verifyAccess() {
        capabilityScope.verifyAccess();
    }

    ProtocolOutputEvent claimTerminal(ProtocolOutputEvent terminal) {
        if (claimedTerminal == null && terminal != null) {
            claimedTerminal = output.refreshTerminal(terminal, deadlineNanos);
        }
        return claimedTerminal;
    }

    ProtocolOutputEvent refreshClaimedTerminal() {
        claimedTerminal =
                output.refreshTerminal(Objects.requireNonNull(claimedTerminal, "claimedTerminal"), deadlineNanos);
        return claimedTerminal;
    }

    void checkDeadline() {
        if (deadlineNanos - System.nanoTime() <= 0) {
            throw failures.timeout(null);
        }
    }

    private ProtocolOutputEvent claimInitialTerminal() {
        if (claimedTerminal != null) {
            return refreshClaimedTerminal();
        }
        if (readStarted) {
            return null;
        }
        // A terminal already pending at the stream's first read predates this read's generic
        // deadline/budget checks. Once an ordinary read starts, those checks keep their priority.
        readStarted = true;
        return claimTerminal(output.peekTerminal(deadlineNanos));
    }
}
