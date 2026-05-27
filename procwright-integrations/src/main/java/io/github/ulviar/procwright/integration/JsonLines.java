package io.github.ulviar.procwright.integration;

import java.util.Objects;

/**
 * JSON Lines framing helpers.
 */
public final class JsonLines {

    private JsonLines() {}

    /**
     * Encodes one JSON value as one JSON Lines frame.
     *
     * @param value JSON value
     * @return compact JSON followed by one line feed
     */
    public static String frame(JsonValue value) {
        return JsonCodec.write(value) + "\n";
    }

    /**
     * Encodes one JSON value as one line without a trailing line separator.
     *
     * @param value JSON value
     * @return compact JSON line
     */
    public static String line(JsonValue value) {
        return JsonCodec.write(value);
    }

    /**
     * Parses one JSON line.
     *
     * @param line line without a frame separator
     * @return parsed JSON value
     */
    public static JsonValue parseLine(String line) {
        requireSingleLine(line);
        return JsonCodec.parse(line);
    }

    private static void requireSingleLine(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain line separators");
        }
    }
}
