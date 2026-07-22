/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.util.Objects;
import java.util.OptionalInt;

/** One queue event with lazily materialized, identity-stable terminal failure state. */
final class ProtocolOutputEvent {

    private final Kind kind;
    private final byte[] bytes;
    private final ProtocolSessionException.Reason reason;
    private final Throwable failure;
    private final OptionalInt processExitCode;
    private volatile ProtocolSessionException materializedFailure;

    private ProtocolOutputEvent(
            Kind kind,
            byte[] bytes,
            ProtocolSessionException.Reason reason,
            Throwable failure,
            OptionalInt processExitCode) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.bytes = bytes;
        this.reason = reason;
        this.failure = failure;
        this.processExitCode = Objects.requireNonNull(processExitCode, "processExitCode");
        if (kind == Kind.BYTES) {
            Objects.requireNonNull(bytes, "bytes");
        }
        if (kind == Kind.FAILURE) {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(failure, "failure");
        }
    }

    static ProtocolOutputEvent bytes(byte[] bytes) {
        return new ProtocolOutputEvent(
                Kind.BYTES, Objects.requireNonNull(bytes, "bytes"), null, null, OptionalInt.empty());
    }

    static ProtocolOutputEvent eof() {
        return eof(OptionalInt.empty());
    }

    static ProtocolOutputEvent eof(OptionalInt processExitCode) {
        return new ProtocolOutputEvent(
                Kind.EOF, null, null, null, Objects.requireNonNull(processExitCode, "processExitCode"));
    }

    static ProtocolOutputEvent closed() {
        return new ProtocolOutputEvent(Kind.CLOSED, null, null, null, OptionalInt.empty());
    }

    static ProtocolOutputEvent failure(ProtocolSessionException.Reason reason, Throwable failure) {
        return new ProtocolOutputEvent(
                Kind.FAILURE,
                null,
                Objects.requireNonNull(reason, "reason"),
                Objects.requireNonNull(failure, "failure"),
                OptionalInt.empty());
    }

    Kind kind() {
        return kind;
    }

    byte[] bytes() {
        return bytes;
    }

    ProtocolSessionException.Reason reason() {
        return reason;
    }

    Throwable failure() {
        return failure;
    }

    boolean isEofWithoutProcessExit() {
        return kind == Kind.EOF && processExitCode.isEmpty();
    }

    ProtocolSessionException terminalFailure(ProtocolRuntimeFailures failures) {
        ProtocolSessionException cached = materializedFailure;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = materializedFailure;
            if (cached == null) {
                cached = createTerminalFailure(Objects.requireNonNull(failures, "failures"));
                materializedFailure = Objects.requireNonNull(cached, "terminal failure");
            }
            return cached;
        }
    }

    private ProtocolSessionException createTerminalFailure(ProtocolRuntimeFailures failures) {
        return switch (kind) {
            case EOF -> processExitCode.isPresent() ? failures.processExited(processExitCode) : failures.eof();
            case CLOSED -> failures.closed(null);
            case FAILURE -> failures.failure(reason, failureMessage(reason), failure);
            case BYTES -> throw new AssertionError("byte event is not terminal");
        };
    }

    private static String failureMessage(ProtocolSessionException.Reason reason) {
        return switch (reason) {
            case DECODE_ERROR -> "Could not decode protocol output";
            case RESPONSE_TOO_LARGE -> "Protocol response exceeded configured size limit";
            case OUTPUT_BACKLOG_OVERFLOW -> "Protocol output backlog overflow";
            default -> "Could not read protocol output";
        };
    }

    enum Kind {
        BYTES,
        EOF,
        CLOSED,
        FAILURE
    }
}
