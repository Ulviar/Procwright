/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class BoundedMatchBufferTest {

    @Test
    void appendWrapsWithoutChangingTheRetainedSuffixOrAbsoluteOffsets() {
        CountingProbe probe = new CountingProbe();
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(8, probe);

        buffer.append("abcde");
        buffer.append("fgh");
        buffer.append("ijk");

        assertEquals(3, buffer.startOffset());
        assertEquals(11, buffer.endOffset());
        assertEquals("defghijk", buffer.snapshot().text());
        assertEquals("fghij", buffer.substring(5, 10));
        assertEquals(11, probe.appendedCharacters);
    }

    @Test
    void chunkLargerThanCapacityRetainsOnlyItsSuffixInLinearWork() {
        CountingProbe probe = new CountingProbe();
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(4, probe);

        buffer.append("old");
        buffer.append("0123456789");

        assertEquals(9, buffer.startOffset());
        assertEquals(13, buffer.endOffset());
        assertEquals("6789", buffer.snapshot().text());
        assertEquals(7, probe.appendedCharacters, "discarded input must not be copied into the ring");
    }

    @Test
    void literalMatcherFindsAcrossRingAndUnicodeChunkBoundaries() {
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(12);
        BoundedMatchBuffer.LiteralMatcher matcher = buffer.literalMatcher("A\uD83D\uDE03B");

        buffer.append("0123456789A\uD83D");
        assertNull(matcher.find(buffer.startOffset()));
        buffer.append("\uDE03B-tail");

        BoundedMatchBuffer.Match match = matcher.find(buffer.startOffset());

        assertEquals("A\uD83D\uDE03B", buffer.substring(match.start(), match.end()));
        assertEquals(10, match.start());
        assertEquals(14, match.end());
    }

    @Test
    void literalMatcherNeverReturnsAStartThatWasTruncated() {
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(5);
        BoundedMatchBuffer.LiteralMatcher matcher = buffer.literalMatcher("TOKEN");

        buffer.append("TOKEN");
        buffer.append("x");

        assertEquals(1, buffer.startOffset());
        assertNull(matcher.find(0));

        buffer.append("TOKEN");

        BoundedMatchBuffer.Match match = matcher.find(0);
        assertEquals(6, match.start());
        assertEquals(11, match.end());
    }

    @Test
    void absentLiteralExaminesOnlyNewCharactersAfterTheInitialSearch() {
        int limit = 64 * 1024;
        int chunkSize = 127;
        int chunks = 4_000;
        CountingProbe probe = new CountingProbe();
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(limit, probe);
        BoundedMatchBuffer.LiteralMatcher matcher = buffer.literalMatcher("not-in-the-output");
        String chunk = "x".repeat(chunkSize);

        for (int index = 0; index < chunks; index++) {
            buffer.append(chunk);
            assertNull(matcher.find(0));
        }

        long inputCharacters = (long) chunkSize * chunks;
        assertTrue(
                probe.literalComparisons <= inputCharacters * 2,
                () -> "KMP search must remain linear, comparisons=" + probe.literalComparisons);
        assertTrue(
                probe.appendedCharacters <= inputCharacters,
                () -> "ring writes must remain linear, copies=" + probe.appendedCharacters);
    }

    @Test
    void evictedPartialMatchFallsBackWithoutRescanningTheRetainedWindow() {
        int limit = 1_024;
        int appends = 10_000;
        CountingProbe probe = new CountingProbe();
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(limit, probe);
        BoundedMatchBuffer.LiteralMatcher matcher = buffer.literalMatcher("a".repeat(limit - 1) + "b");
        buffer.append("a".repeat(limit));
        assertNull(matcher.find(0));
        long initialComparisons = probe.literalComparisons;

        for (int index = 0; index < appends; index++) {
            buffer.append("aa");
            assertNull(matcher.find(0));
        }

        long repeatedComparisons = probe.literalComparisons - initialComparisons;
        assertTrue(
                repeatedComparisons <= appends * 6L,
                () -> "failure-link recovery must be O(appended output), comparisons=" + repeatedComparisons);
    }

    @Test
    void literalLongerThanCapacityDoesNotAllocateFailureState() {
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(8);

        BoundedMatchBuffer.LiteralMatcher matcher = buffer.literalMatcher("x".repeat(1_000_000));

        assertEquals(0, matcher.failureStateCount());
        buffer.append("x".repeat(32));
        assertNull(matcher.find(0));
    }

    @Test
    void literalCursorControlsOverlappingAndNonOverlappingMatches() {
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(8);
        buffer.append("ababa");

        BoundedMatchBuffer.Match first = buffer.literalMatcher("aba").find(0);
        BoundedMatchBuffer.Match overlapping = buffer.literalMatcher("aba").find(2);
        BoundedMatchBuffer.Match nonOverlapping = buffer.literalMatcher("aba").find(first.end());

        assertEquals(new BoundedMatchBuffer.Match(0, 3), first);
        assertEquals(new BoundedMatchBuffer.Match(2, 5), overlapping);
        assertNull(nonOverlapping);
    }

    @Test
    void advancedCursorInvalidatesOnlyTheObsoletePartialState() {
        CountingProbe probe = new CountingProbe();
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(16, probe);
        BoundedMatchBuffer.LiteralMatcher matcher = buffer.literalMatcher("abc");
        buffer.append("ab");
        assertNull(matcher.find(0));

        assertNull(matcher.find(1));
        long comparisonsBeforeAppend = probe.literalComparisons;
        buffer.append("abc");

        BoundedMatchBuffer.Match match = matcher.find(1);
        assertEquals(new BoundedMatchBuffer.Match(2, 5), match);
        assertTrue(probe.literalComparisons - comparisonsBeforeAppend <= 4);
    }

    @Test
    void randomizedIncrementalMatchingAgreesWithBoundedStringReference() {
        for (int seed = 0; seed < 200; seed++) {
            Random random = new Random(seed);
            int limit = 1 + random.nextInt(32);
            String literal = randomText(random, random.nextInt(limit + 3));
            BoundedMatchBuffer buffer = new BoundedMatchBuffer(limit);
            BoundedMatchBuffer.LiteralMatcher matcher = buffer.literalMatcher(literal);
            StringBuilder retained = new StringBuilder();
            long retainedOffset = 0;
            long cursor = 0;

            for (int step = 0; step < 250; step++) {
                String chunk = randomText(random, random.nextInt(limit * 2 + 1));
                buffer.append(chunk);
                retained.append(chunk);
                if (retained.length() > limit) {
                    int removed = retained.length() - limit;
                    retained.delete(0, removed);
                    retainedOffset += removed;
                }
                if (random.nextInt(11) == 0) {
                    long available = retainedOffset + retained.length() - Math.max(cursor, retainedOffset);
                    cursor = Math.max(cursor, retainedOffset) + random.nextInt(Math.toIntExact(available) + 1);
                }

                long effectiveCursor = Math.max(cursor, retainedOffset);
                int expectedIndex = retained.indexOf(literal, Math.toIntExact(effectiveCursor - retainedOffset));
                BoundedMatchBuffer.Match actual = matcher.find(cursor);
                if (expectedIndex < 0) {
                    assertNull(actual, "seed=" + seed + ", step=" + step + ", literal=" + literal);
                    continue;
                }

                assertNotNull(actual);
                long expectedStart = retainedOffset + expectedIndex;
                assertEquals(expectedStart, actual.start(), "seed=" + seed + ", step=" + step);
                assertEquals(expectedStart + literal.length(), actual.end(), "seed=" + seed + ", step=" + step);
                assertEquals(literal, buffer.substring(actual.start(), actual.end()));
                cursor = actual.end();
                matcher = buffer.literalMatcher(literal);
            }
        }
    }

    @Test
    void regexSnapshotIsCopiedOncePerOutputRevision() {
        CountingProbe probe = new CountingProbe();
        BoundedMatchBuffer buffer = new BoundedMatchBuffer(8, probe);
        buffer.append("abcdef");

        BoundedMatchBuffer.Snapshot first = buffer.snapshot();
        BoundedMatchBuffer.Snapshot second = buffer.snapshot();

        assertSame(first, second);
        assertEquals(6, probe.snapshottedCharacters);

        buffer.append("ghi");
        BoundedMatchBuffer.Snapshot third = buffer.snapshot();

        assertEquals("bcdefghi", third.text());
        assertEquals(14, probe.snapshottedCharacters);
    }

    private static final class CountingProbe implements BoundedMatchBuffer.WorkProbe {

        private long appendedCharacters;
        private long literalComparisons;
        private long snapshottedCharacters;

        @Override
        public void appended(int count) {
            appendedCharacters += count;
        }

        @Override
        public void compared(int count) {
            literalComparisons += count;
        }

        @Override
        public void snapshotted(int count) {
            snapshottedCharacters += count;
        }
    }

    private static String randomText(Random random, int length) {
        char[] alphabet = {'a', 'b', 'c', '\u00E9', '\uD83D', '\uDE03'};
        StringBuilder text = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            text.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return text.toString();
    }
}
