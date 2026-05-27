package io.github.ulviar.procwright.session;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Filters process output before expect matching and transcript capture.
 */
@FunctionalInterface
public interface ExpectOutputFilter {

    /**
     * Applies this filter to an output chunk.
     *
     * @param output output chunk
     * @return filtered output
     */
    String apply(String output);

    /**
     * Returns a filter that leaves output unchanged.
     *
     * @return identity filter
     */
    static ExpectOutputFilter identity() {
        return output -> Objects.requireNonNull(output, "output");
    }

    /**
     * Returns a filter that strips common ANSI control sequences.
     *
     * @return ANSI-stripping filter
     */
    static ExpectOutputFilter stripAnsiControlSequences() {
        Pattern ansiControlSequence = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");
        return output -> ansiControlSequence.matcher(output).replaceAll("");
    }
}
