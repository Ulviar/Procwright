/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LaunchSettingsTest {

    @Test
    void constructorCopiesCallerCollectionsAndMap() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("original");
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("ORIGINAL", "value");

        LaunchSettings settings =
                new LaunchSettings("tool", false, arguments, Optional.empty(), EnvironmentPolicy.INHERIT, environment);

        arguments.set(0, "mutated");
        environment.clear();

        assertEquals(java.util.List.of("original"), settings.arguments());
        assertEquals(java.util.Map.of("ORIGINAL", "value"), settings.environment());
        assertThrows(UnsupportedOperationException.class, () -> settings.environment()
                .put("LATE", "value"));
    }

    @Test
    void bulkAppendPreservesIdentityOrderAndIndependentBranches() {
        LaunchSettings base = settings().withArg("base");

        assertSame(base, base.withArgs(List.of()));
        assertSame(base, base.withArgs(new String[0]));

        ArrayList<String> shared = new ArrayList<>(List.of("first", "second"));
        LaunchSettings left = base.withArgs(shared);
        LaunchSettings right = base.withArgs("right-1", "right-2");
        shared.clear();

        assertNotSame(base, left);
        assertNotSame(base, right);
        assertEquals(List.of("base"), base.arguments());
        assertEquals(List.of("base", "first", "second"), left.arguments());
        assertEquals(List.of("base", "right-1", "right-2"), right.arguments());
    }

    @Test
    void invalidBulkArgumentsFailAtomicallyAtEveryPosition() {
        LaunchSettings original = settings().withArg("base");

        for (int invalidIndex = 0; invalidIndex < 3; invalidIndex++) {
            ArrayList<String> nullArguments = new ArrayList<>(Arrays.asList("first", "middle", "last"));
            nullArguments.set(invalidIndex, null);
            assertThrows(NullPointerException.class, () -> original.withArgs(nullArguments));
            assertThrows(NullPointerException.class, () -> original.withArgs(nullArguments.toArray(String[]::new)));
            assertEquals(List.of("base"), original.arguments());

            ArrayList<String> nulArguments = new ArrayList<>(Arrays.asList("first", "middle", "last"));
            nulArguments.set(invalidIndex, "hidden\0argument");
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> original.withArgs(nulArguments));
            IllegalArgumentException varargsFailure = assertThrows(
                    IllegalArgumentException.class, () -> original.withArgs(nulArguments.toArray(String[]::new)));
            assertEquals("argument must not contain NUL", failure.getMessage());
            assertEquals("argument must not contain NUL", varargsFailure.getMessage());
            assertEquals(List.of("base"), original.arguments());
        }
    }

    @Test
    void shellBulkAppendPreservesIdentityAndSemanticFailure() {
        LaunchSettings shell = new LaunchSettings(
                "echo ready", true, List.of(), Optional.empty(), EnvironmentPolicy.INHERIT, java.util.Map.of());

        assertSame(shell, shell.withArgs(List.of()));
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> shell.withArgs(List.of("unexpected")));

        assertEquals("shell commands do not accept argv arguments", failure.getMessage());
    }

    @Test
    void largeBulkAppendTakesOneCallerSnapshotWithoutIteratingTheCallerAgain() {
        CountingSnapshotCollection additions =
                new CountingSnapshotCollection(java.util.stream.IntStream.range(0, 20_000)
                        .mapToObj(index -> "argument-" + index)
                        .toList());

        LaunchSettings result = settings().withArgs(additions);

        assertEquals(20_000, result.arguments().size());
        assertEquals("argument-0", result.arguments().get(0));
        assertEquals("argument-19999", result.arguments().get(19_999));
        assertEquals(1, additions.snapshotCalls());
        assertEquals(0, additions.iteratorCalls());
    }

    private static LaunchSettings settings() {
        return new LaunchSettings(
                "tool", false, List.of(), Optional.empty(), EnvironmentPolicy.INHERIT, java.util.Map.of());
    }

    private static final class CountingSnapshotCollection extends AbstractCollection<String> {

        private final List<String> values;
        private final AtomicInteger snapshotCalls = new AtomicInteger();
        private final AtomicInteger iteratorCalls = new AtomicInteger();

        private CountingSnapshotCollection(List<String> values) {
            this.values = List.copyOf(values);
        }

        @Override
        public Iterator<String> iterator() {
            iteratorCalls.incrementAndGet();
            return values.iterator();
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Object[] toArray() {
            snapshotCalls.incrementAndGet();
            return values.toArray();
        }

        private int snapshotCalls() {
            return snapshotCalls.get();
        }

        private int iteratorCalls() {
            return iteratorCalls.get();
        }
    }
}
