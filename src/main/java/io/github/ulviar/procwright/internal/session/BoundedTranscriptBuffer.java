/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;

final class BoundedTranscriptBuffer {

    private final int limit;
    private final char[] text;
    private int start;
    private int size;
    private String currentLabel;
    private boolean atLineStart = true;
    private boolean truncated;

    BoundedTranscriptBuffer(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.text = new char[limit];
    }

    synchronized void appendAction(String action) {
        Objects.requireNonNull(action, "action");
        if (!atLineStart) {
            append('\n');
        }
        append(action);
        append('\n');
        atLineStart = true;
        currentLabel = null;
    }

    synchronized boolean appendStream(String label, char[] chars, int count) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(chars, "chars");
        Objects.checkFromIndexSize(0, count, chars.length);
        boolean alreadyTruncated = truncated;
        for (int index = 0; index < count; index++) {
            appendLabeledChar(label, chars[index]);
        }
        return truncated && !alreadyTruncated;
    }

    synchronized boolean appendStream(String label, String chunk) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(chunk, "chunk");
        boolean alreadyTruncated = truncated;
        for (int index = 0; index < chunk.length(); index++) {
            appendLabeledChar(label, chunk.charAt(index));
        }
        return truncated && !alreadyTruncated;
    }

    synchronized Snapshot snapshot() {
        char[] snapshot = new char[size];
        int firstPart = Math.min(size, limit - start);
        System.arraycopy(text, start, snapshot, 0, firstPart);
        if (firstPart < size) {
            System.arraycopy(text, 0, snapshot, firstPart, size - firstPart);
        }
        return new Snapshot(new String(snapshot), truncated);
    }

    int limit() {
        return limit;
    }

    private void appendLabeledChar(String label, char value) {
        if (atLineStart || !label.equals(currentLabel)) {
            if (!atLineStart) {
                append('\n');
            }
            append(label);
            append(": ");
            atLineStart = false;
            currentLabel = label;
        }
        append(value);
        if (value == '\n') {
            atLineStart = true;
            currentLabel = null;
        }
    }

    private void append(CharSequence value) {
        for (int index = 0; index < value.length(); index++) {
            append(value.charAt(index));
        }
    }

    private void append(char value) {
        if (size < limit) {
            text[(start + size) % limit] = value;
            size++;
            return;
        }
        text[start] = value;
        start = (start + 1) % limit;
        truncated = true;
    }

    record Snapshot(String text, boolean truncated) {
        Snapshot {
            Objects.requireNonNull(text, "text");
        }
    }
}
