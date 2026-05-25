package io.github.ulviar.icli.integration;

import java.util.Objects;
import java.util.function.Function;

/**
 * Narrow wrapper for exposing CLI-backed work as structured tool-call results.
 *
 * @param <I> input type
 * @param <O> output type
 */
@FunctionalInterface
public interface CommandBackedTool<I, O> {

    /**
     * Executes one tool call and returns a structured observation.
     *
     * @param input input payload
     * @return success or structured failure
     */
    ToolCallResult<O> call(I input);

    /**
     * Creates a tool wrapper around a throwing handler.
     *
     * @param handler tool handler
     * @param <I> input type
     * @param <O> output type
     * @return command-backed tool
     */
    static <I, O> CommandBackedTool<I, O> of(Handler<I, O> handler) {
        Objects.requireNonNull(handler, "handler");
        return input -> {
            try {
                return ToolCallResult.success(handler.handle(input));
            } catch (Exception exception) {
                return ToolCallResult.failure(exception);
            }
        };
    }

    /**
     * Creates a JSON Lines tool over an existing JSON line session.
     *
     * @param session JSON line session
     * @param requestEncoder input to JSON request mapper
     * @param responseDecoder JSON response to output mapper
     * @param <I> input type
     * @param <O> output type
     * @return command-backed tool
     */
    static <I, O> CommandBackedTool<I, O> jsonLine(
            JsonLineSession session, Function<I, JsonValue> requestEncoder, Function<JsonValue, O> responseDecoder) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(requestEncoder, "requestEncoder");
        Objects.requireNonNull(responseDecoder, "responseDecoder");
        return of(input -> responseDecoder.apply(session.request(requestEncoder.apply(input))));
    }

    /**
     * Handler that may throw. Failures are mapped to {@link CliAdapterError}.
     *
     * @param <I> input type
     * @param <O> output type
     */
    @FunctionalInterface
    interface Handler<I, O> {

        /**
         * Handles one input.
         *
         * @param input input payload
         * @return output payload
         * @throws Exception when the underlying command workflow fails
         */
        O handle(I input) throws Exception;
    }
}
