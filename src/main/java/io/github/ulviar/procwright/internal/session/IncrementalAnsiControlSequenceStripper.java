/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/** Incrementally removes 7-bit ECMA-48 CSI sequences without retaining an unbounded partial sequence. */
final class IncrementalAnsiControlSequenceStripper {

    static final int MAX_PARTIAL_CHARS = 256;

    private final StringBuilder partial = new StringBuilder();
    private State state = State.TEXT;

    String append(String text) {
        StringBuilder output = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            accept(text.charAt(index), output);
        }
        return output.toString();
    }

    String finish() {
        if (partial.isEmpty()) {
            return "";
        }
        String unfinished = partial.toString();
        partial.setLength(0);
        state = State.TEXT;
        return unfinished;
    }

    int retainedCharsForTest() {
        return partial.length();
    }

    private void accept(char value, StringBuilder output) {
        switch (state) {
            case TEXT -> acceptText(value, output);
            case ESCAPE -> acceptEscape(value, output);
            case CSI_PARAMETERS -> acceptCsiParameter(value, output);
            case CSI_INTERMEDIATES -> acceptCsiIntermediate(value, output);
        }
    }

    private void acceptText(char value, StringBuilder output) {
        if (value == '\u001B') {
            partial.append(value);
            state = State.ESCAPE;
        } else {
            output.append(value);
        }
    }

    private void acceptEscape(char value, StringBuilder output) {
        if (value == '\u001B') {
            restartAtEscape(output);
            return;
        }
        partial.append(value);
        if (flushIfOverlong(output)) {
            return;
        }
        if (value == '[') {
            state = State.CSI_PARAMETERS;
        } else {
            flushPartial(output);
        }
    }

    private void acceptCsiParameter(char value, StringBuilder output) {
        if (value == '\u001B') {
            restartAtEscape(output);
            return;
        }
        partial.append(value);
        if (flushIfOverlong(output)) {
            return;
        }
        if (isParameter(value)) {
            return;
        }
        if (isIntermediate(value)) {
            state = State.CSI_INTERMEDIATES;
        } else if (isFinal(value)) {
            discardPartial();
        } else {
            flushPartial(output);
        }
    }

    private void acceptCsiIntermediate(char value, StringBuilder output) {
        if (value == '\u001B') {
            restartAtEscape(output);
            return;
        }
        partial.append(value);
        if (flushIfOverlong(output)) {
            return;
        }
        if (isIntermediate(value)) {
            return;
        }
        if (isFinal(value)) {
            discardPartial();
        } else {
            flushPartial(output);
        }
    }

    private boolean flushIfOverlong(StringBuilder output) {
        if (partial.length() > MAX_PARTIAL_CHARS) {
            flushPartial(output);
            return true;
        }
        return false;
    }

    private void restartAtEscape(StringBuilder output) {
        flushPartial(output);
        partial.append('\u001B');
        state = State.ESCAPE;
    }

    private void flushPartial(StringBuilder output) {
        output.append(partial);
        partial.setLength(0);
        state = State.TEXT;
    }

    private void discardPartial() {
        partial.setLength(0);
        state = State.TEXT;
    }

    private static boolean isParameter(char value) {
        return value >= 0x30 && value <= 0x3F;
    }

    private static boolean isIntermediate(char value) {
        return value >= 0x20 && value <= 0x2F;
    }

    private static boolean isFinal(char value) {
        return value >= 0x40 && value <= 0x7E;
    }

    private enum State {
        TEXT,
        ESCAPE,
        CSI_PARAMETERS,
        CSI_INTERMEDIATES
    }
}
