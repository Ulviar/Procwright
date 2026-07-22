/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.Objects;

/** Bounded, monitor-confined character storage for expect matching. */
final class BoundedMatchBuffer {

    private static final int[] NO_FAILURE_STATES = new int[0];
    private static final WorkProbe NO_WORK_PROBE = new WorkProbe() {
        @Override
        public void appended(int count) {}

        @Override
        public void compared(int count) {}

        @Override
        public void snapshotted(int count) {}
    };

    private final char[] characters;
    private final WorkProbe workProbe;
    private int start;
    private int size;
    private long startOffset;
    private long revision;
    private Snapshot cachedSnapshot;

    BoundedMatchBuffer(int limit) {
        this(limit, NO_WORK_PROBE);
    }

    BoundedMatchBuffer(int limit, WorkProbe workProbe) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.characters = new char[limit];
        this.workProbe = Objects.requireNonNull(workProbe, "workProbe");
    }

    static WorkProbe noWorkProbe() {
        return NO_WORK_PROBE;
    }

    void append(String chunk) {
        Objects.requireNonNull(chunk, "chunk");
        int incoming = chunk.length();
        if (incoming == 0) {
            return;
        }

        long previousEnd = endOffset();
        if (incoming >= characters.length) {
            int retained = characters.length;
            chunk.getChars(incoming - retained, incoming, characters, 0);
            start = 0;
            size = retained;
            startOffset = previousEnd + incoming - retained;
            workProbe.appended(retained);
        } else {
            int overflow = Math.max(0, size + incoming - characters.length);
            start = (start + overflow) % characters.length;
            size -= overflow;
            startOffset += overflow;
            copyToEnd(chunk);
            workProbe.appended(incoming);
        }
        revision++;
        cachedSnapshot = null;
    }

    LiteralMatcher literalMatcher(String literal) {
        return new LiteralMatcher(Objects.requireNonNull(literal, "literal"));
    }

    long startOffset() {
        return startOffset;
    }

    long endOffset() {
        return startOffset + size;
    }

    long revision() {
        return revision;
    }

    String substring(long absoluteStart, long absoluteEnd) {
        if (absoluteStart < startOffset || absoluteEnd < absoluteStart || absoluteEnd > endOffset()) {
            throw new IndexOutOfBoundsException("range [%d, %d) is outside retained output [%d, %d)"
                    .formatted(absoluteStart, absoluteEnd, startOffset, endOffset()));
        }
        int length = Math.toIntExact(absoluteEnd - absoluteStart);
        char[] copy = new char[length];
        copyRetained(Math.toIntExact(absoluteStart - startOffset), copy, 0, length);
        return new String(copy);
    }

    Snapshot snapshot() {
        if (cachedSnapshot == null) {
            char[] copy = new char[size];
            copyRetained(0, copy, 0, size);
            cachedSnapshot = new Snapshot(new String(copy), startOffset, revision);
            workProbe.snapshotted(size);
        }
        return cachedSnapshot;
    }

    private void copyToEnd(String chunk) {
        int incoming = chunk.length();
        int writeStart = (start + size) % characters.length;
        int first = Math.min(incoming, characters.length - writeStart);
        chunk.getChars(0, first, characters, writeStart);
        if (first < incoming) {
            chunk.getChars(first, incoming, characters, 0);
        }
        size += incoming;
    }

    private void copyRetained(int logicalStart, char[] target, int targetStart, int length) {
        if (length == 0) {
            return;
        }
        int physicalStart = (start + logicalStart) % characters.length;
        int first = Math.min(length, characters.length - physicalStart);
        System.arraycopy(characters, physicalStart, target, targetStart, first);
        if (first < length) {
            System.arraycopy(characters, 0, target, targetStart + first, length - first);
        }
    }

    private char charAt(long absoluteOffset) {
        if (absoluteOffset < startOffset || absoluteOffset >= endOffset()) {
            throw new IndexOutOfBoundsException("offset is outside retained output: " + absoluteOffset);
        }
        int logicalIndex = Math.toIntExact(absoluteOffset - startOffset);
        return characters[(start + logicalIndex) % characters.length];
    }

    /** Stateful KMP matcher that consumes each newly retained character at most once. */
    final class LiteralMatcher {

        private final String literal;
        private final int[] failure;
        private final boolean matchable;
        private long nextOffset = Long.MIN_VALUE;
        private int matched;

        private LiteralMatcher(String literal) {
            this.literal = literal;
            this.matchable = literal.length() <= characters.length;
            this.failure = matchable ? failureTable(literal) : NO_FAILURE_STATES;
        }

        Match find(long minimumStart) {
            long effectiveStart = Math.max(minimumStart, startOffset);
            if (literal.isEmpty()) {
                return new Match(Math.min(effectiveStart, endOffset()), Math.min(effectiveStart, endOffset()));
            }
            if (!matchable) {
                nextOffset = endOffset();
                matched = 0;
                return null;
            }

            int comparisons = 0;
            if (nextOffset == Long.MIN_VALUE || nextOffset < effectiveStart || nextOffset > endOffset()) {
                nextOffset = effectiveStart;
                matched = 0;
            } else {
                long retainedPrefixLength = nextOffset - effectiveStart;
                while (matched > retainedPrefixLength) {
                    comparisons++;
                    matched = failure[matched - 1];
                }
            }

            while (nextOffset < endOffset()) {
                char next = charAt(nextOffset);
                while (matched > 0 && literal.charAt(matched) != next) {
                    comparisons++;
                    matched = failure[matched - 1];
                }
                comparisons++;
                if (literal.charAt(matched) == next) {
                    matched++;
                }
                nextOffset++;
                if (matched == literal.length()) {
                    long matchEnd = nextOffset;
                    long matchStart = matchEnd - literal.length();
                    matched = failure[matched - 1];
                    workProbe.compared(comparisons);
                    return new Match(matchStart, matchEnd);
                }
            }
            workProbe.compared(comparisons);
            return null;
        }

        int failureStateCount() {
            return failure.length;
        }

        private static int[] failureTable(String literal) {
            int[] table = new int[literal.length()];
            int prefix = 0;
            for (int index = 1; index < literal.length(); index++) {
                char next = literal.charAt(index);
                while (prefix > 0 && literal.charAt(prefix) != next) {
                    prefix = table[prefix - 1];
                }
                if (literal.charAt(prefix) == next) {
                    prefix++;
                }
                table[index] = prefix;
            }
            return table;
        }
    }

    interface WorkProbe {

        void appended(int count);

        void compared(int count);

        void snapshotted(int count);
    }

    record Match(long start, long end) {
        Match {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid match range");
            }
        }
    }

    record Snapshot(String text, long offset, long revision) {
        Snapshot {
            Objects.requireNonNull(text, "text");
        }
    }
}
