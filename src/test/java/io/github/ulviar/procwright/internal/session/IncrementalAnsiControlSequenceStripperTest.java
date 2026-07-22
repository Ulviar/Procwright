/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IncrementalAnsiControlSequenceStripperTest {

    private static final String COLORED = "before\u001B[31mREADY\u001B[0mafter";

    @Test
    void stripsCsiSequencesAcrossEveryChunkBoundary() {
        for (int split = 0; split <= COLORED.length(); split++) {
            IncrementalAnsiControlSequenceStripper stripper = new IncrementalAnsiControlSequenceStripper();

            String actual = stripper.append(COLORED.substring(0, split))
                    + stripper.append(COLORED.substring(split))
                    + stripper.finish();

            assertEquals("beforeREADYafter", actual, "split=" + split);
        }
    }

    @Test
    void streamInstancesKeepIndependentPartialSequences() {
        IncrementalAnsiControlSequenceStripper stdout = new IncrementalAnsiControlSequenceStripper();
        IncrementalAnsiControlSequenceStripper stderr = new IncrementalAnsiControlSequenceStripper();

        assertEquals("out", stdout.append("out\u001B["));
        assertEquals("err", stderr.append("err\u001B["));
        assertEquals("READY", stdout.append("31mREADY"));
        assertEquals("FAIL", stderr.append("1mFAIL"));
        assertEquals("", stdout.finish());
        assertEquals("", stderr.finish());
    }

    @Test
    void incompleteSequenceIsEmittedVerbatimAtEndOfStream() {
        String unfinished = "prefix\u001B[31";
        for (int split = 0; split <= unfinished.length(); split++) {
            IncrementalAnsiControlSequenceStripper stripper = new IncrementalAnsiControlSequenceStripper();

            String actual = stripper.append(unfinished.substring(0, split))
                    + stripper.append(unfinished.substring(split))
                    + stripper.finish();

            assertEquals(unfinished, actual, "split=" + split);
            assertEquals("", stripper.finish(), "split=" + split);
        }
    }

    @Test
    void sequencesAtOrBelowThePartialLimitAreStrippedAtEveryChunkBoundary() {
        for (int parameterChars : new int[] {252, 253}) {
            String input = "before\u001B[" + "1".repeat(parameterChars) + "mTAIL";
            for (int split = 0; split <= input.length(); split++) {
                IncrementalAnsiControlSequenceStripper stripper = new IncrementalAnsiControlSequenceStripper();

                String actual = stripper.append(input.substring(0, split))
                        + stripper.append(input.substring(split))
                        + stripper.finish();

                assertEquals("beforeTAIL", actual, "parameters=" + parameterChars + ", split=" + split);
                assertTrue(stripper.retainedCharsForTest() <= IncrementalAnsiControlSequenceStripper.MAX_PARTIAL_CHARS);
            }
        }
    }

    @Test
    void firstOverlongSequenceIsPreservedAtEveryChunkBoundaryWithoutUnboundedRetention() {
        String overlong = "\u001B[" + "1".repeat(254) + "mTAIL";
        for (int split = 0; split <= overlong.length(); split++) {
            IncrementalAnsiControlSequenceStripper stripper = new IncrementalAnsiControlSequenceStripper();

            String actual = stripper.append(overlong.substring(0, split))
                    + stripper.append(overlong.substring(split))
                    + stripper.finish();

            assertEquals(overlong, actual, "split=" + split);
            assertTrue(stripper.retainedCharsForTest() <= IncrementalAnsiControlSequenceStripper.MAX_PARTIAL_CHARS);
        }
    }

    @Test
    void malformedCsiCandidateIsPreserved() {
        IncrementalAnsiControlSequenceStripper stripper = new IncrementalAnsiControlSequenceStripper();

        assertEquals("a\u001B[31\nb", stripper.append("a\u001B[31\nb"));
        assertEquals("", stripper.finish());
    }

    @Test
    void escapeThatInvalidatesOneCandidateStartsAnAdjacentValidCandidateAtEveryChunkBoundary() {
        String input = "before\u001B[31\u001B[32mREADY";
        for (int split = 0; split <= input.length(); split++) {
            IncrementalAnsiControlSequenceStripper stripper = new IncrementalAnsiControlSequenceStripper();

            String actual = stripper.append(input.substring(0, split))
                    + stripper.append(input.substring(split))
                    + stripper.finish();

            assertEquals("before\u001B[31READY", actual, "split=" + split);
        }
    }
}
