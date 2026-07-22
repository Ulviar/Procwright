/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;

/** Zero-copy character view that appends one line feed to existing text. */
final class LineFeedTerminatedText implements CharSequence {

    private final CharSequence text;

    LineFeedTerminatedText(CharSequence text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    @Override
    public int length() {
        return Math.addExact(text.length(), 1);
    }

    @Override
    public char charAt(int index) {
        Objects.checkIndex(index, length());
        return index == text.length() ? '\n' : text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        Objects.checkFromToIndex(start, end, length());
        return new Slice(this, start, end);
    }

    private record Slice(CharSequence source, int start, int end) implements CharSequence {

        private Slice {
            Objects.requireNonNull(source, "source");
            Objects.checkFromToIndex(start, end, source.length());
        }

        @Override
        public int length() {
            return end - start;
        }

        @Override
        public char charAt(int index) {
            Objects.checkIndex(index, length());
            return source.charAt(start + index);
        }

        @Override
        public CharSequence subSequence(int nestedStart, int nestedEnd) {
            Objects.checkFromToIndex(nestedStart, nestedEnd, length());
            return new Slice(source, start + nestedStart, start + nestedEnd);
        }
    }
}
