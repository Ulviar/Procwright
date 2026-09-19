/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class BoundedCharacterStagingTest {

    @Test
    void acceptsExactlyTheLimitAndRejectsExcessBeforeAppending() throws Exception {
        BoundedCharacterStaging staged = new BoundedCharacterStaging();
        staged.reset(3);
        staged.accept(new char[] {'a', 'b'}, 2);
        staged.accept(new char[] {'c'}, 1);

        assertThrows(IncrementalTextDecoder.DecoderStateException.class, () -> staged.accept(new char[] {'d'}, 1));
        StringBuilder published = new StringBuilder();
        staged.publishTo((chars, count) -> published.append(chars, 0, count));

        assertEquals("abc", published.toString());
        assertEquals(0, staged.remainingCapacity());
    }

    @Test
    void nextOperationReusesStorageWithoutPublishingDiscardedOutput() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        BoundedCharacterStaging staged = new BoundedCharacterStaging(capacity -> {
            allocations.incrementAndGet();
            return new char[capacity];
        });
        staged.reset(Integer.MAX_VALUE);
        assertEquals(0, allocations.get());
        staged.accept(new char[] {'a', 'b', 'c'}, 3);
        staged.finishOperation();
        staged.reset(1);
        assertThrows(IncrementalTextDecoder.DecoderStateException.class, () -> staged.accept(new char[] {'x', 'y'}, 2));
        staged.accept(new char[] {'z'}, 1);
        StringBuilder published = new StringBuilder();
        staged.publishTo((chars, count) -> published.append(chars, 0, count));

        assertEquals("z", published.toString());
        assertEquals(1, allocations.get());
    }

    @Test
    void completedLargeOperationDoesNotRetainItsPeakStorage() throws Exception {
        AtomicInteger allocations = new AtomicInteger();
        BoundedCharacterStaging staged = new BoundedCharacterStaging(capacity -> {
            allocations.incrementAndGet();
            return new char[capacity];
        });
        staged.reset(128 * 1024);
        staged.accept(new char[128 * 1024], 128 * 1024);
        staged.finishOperation();
        staged.reset(1);
        staged.accept(new char[] {'a'}, 1);

        assertEquals(2, allocations.get());
        assertEquals(1, staged.length());
    }
}
