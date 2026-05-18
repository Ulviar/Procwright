package com.github.ulviar.icli.internal.session;

import java.util.Objects;

final class BoundedTranscriptBuffer {

    private final int limit;
    private final StringBuilder text = new StringBuilder();
    private String currentLabel;
    private boolean atLineStart = true;
    private boolean truncated;

    BoundedTranscriptBuffer(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    int limit() {
        return limit;
    }

    synchronized void appendAction(String action) {
        Objects.requireNonNull(action, "action");
        if (!atLineStart) {
            text.append('\n');
        }
        text.append(action).append('\n');
        atLineStart = true;
        currentLabel = null;
        trim();
    }

    synchronized boolean appendStream(String label, char[] chars, int count) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(chars, "chars");
        Objects.checkFromIndexSize(0, count, chars.length);
        boolean alreadyTruncated = truncated;
        for (int index = 0; index < count; index++) {
            appendLabeledChar(label, chars[index]);
        }
        trim();
        return truncated && !alreadyTruncated;
    }

    synchronized boolean appendStream(String label, String chunk) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(chunk, "chunk");
        boolean alreadyTruncated = truncated;
        for (int index = 0; index < chunk.length(); index++) {
            appendLabeledChar(label, chunk.charAt(index));
        }
        trim();
        return truncated && !alreadyTruncated;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(text.toString(), truncated);
    }

    private void appendLabeledChar(String label, char value) {
        if (atLineStart || !label.equals(currentLabel)) {
            if (!atLineStart) {
                text.append('\n');
            }
            text.append(label).append(": ");
            atLineStart = false;
            currentLabel = label;
        }
        text.append(value);
        if (value == '\n') {
            atLineStart = true;
            currentLabel = null;
        }
    }

    private void trim() {
        if (text.length() > limit) {
            text.delete(0, text.length() - limit);
            truncated = true;
        }
    }

    record Snapshot(String text, boolean truncated) {
        Snapshot {
            Objects.requireNonNull(text, "text");
        }
    }
}
