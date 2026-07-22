/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ActivityInputStreamTest {

    @Test
    void successfulSkipMarksActivityButZeroSkipDoesNot() throws Exception {
        AtomicInteger activities = new AtomicInteger();
        ActivityInputStream input =
                new ActivityInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), activities::incrementAndGet);

        assertEquals(2, input.skip(2));
        assertEquals(1, activities.get());
        assertEquals(0, input.skip(0));
        assertEquals(1, activities.get());
        assertEquals(1, input.skip(10));
        assertEquals(2, activities.get());
        assertEquals(0, input.skip(1));
        assertEquals(2, activities.get());
    }
}
