package com.github.ulviar.icli.integration;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured result of a CLI-backed tool call.
 *
 * @param <T> success payload type
 */
public sealed interface ToolCallResult<T> permits ToolCallResult.Success, ToolCallResult.Failure {

    /**
     * Creates a successful result.
     *
     * @param value success payload
     * @param <T> payload type
     * @return success result
     */
    static <T> ToolCallResult<T> success(T value) {
        return new Success<>(value);
    }

    /**
     * Creates a failed result.
     *
     * @param error structured error
     * @param <T> payload type
     * @return failure result
     */
    static <T> ToolCallResult<T> failure(CliAdapterError error) {
        return new Failure<>(error);
    }

    /**
     * Maps a throwable to a failed result.
     *
     * @param throwable failure
     * @param <T> payload type
     * @return failure result
     */
    static <T> ToolCallResult<T> failure(Throwable throwable) {
        return failure(CliAdapterError.from(throwable));
    }

    /**
     * Returns whether this result is successful.
     *
     * @return {@code true} for success
     */
    boolean succeeded();

    /**
     * Returns the success payload when present.
     *
     * @return success payload
     */
    Optional<T> value();

    /**
     * Returns the structured error when present.
     *
     * @return structured error
     */
    Optional<CliAdapterError> error();

    /**
     * Successful tool call.
     *
     * @param payload success payload
     * @param <T> payload type
     */
    record Success<T>(T payload) implements ToolCallResult<T> {

        /**
         * Creates a success result.
         *
         * @param payload success payload
         */
        public Success {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public boolean succeeded() {
            return true;
        }

        @Override
        public Optional<T> value() {
            return Optional.of(payload);
        }

        @Override
        public Optional<CliAdapterError> error() {
            return Optional.empty();
        }
    }

    /**
     * Failed tool call.
     *
     * @param adapterError structured error
     * @param <T> payload type
     */
    record Failure<T>(CliAdapterError adapterError) implements ToolCallResult<T> {

        /**
         * Creates a failure result.
         *
         * @param adapterError structured error
         */
        public Failure {
            Objects.requireNonNull(adapterError, "adapterError");
        }

        @Override
        public boolean succeeded() {
            return false;
        }

        @Override
        public Optional<T> value() {
            return Optional.empty();
        }

        @Override
        public Optional<CliAdapterError> error() {
            return Optional.of(adapterError);
        }
    }
}
