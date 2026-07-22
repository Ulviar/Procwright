/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CommandSpecTest {

    @Test
    void rejectsBlankExecutable() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.of(" "));
    }

    @Test
    void rejectsBlankShellCommand() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.shell(" "));
    }

    @Test
    void rejectsNulExecutableWithoutEchoingIt() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> CommandSpec.of("hidden\0tool"));

        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    void rejectsNulShellCommandWithoutEchoingIt() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> CommandSpec.shell("hidden\0command"));

        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    void rejectsNulArgumentWithoutEchoingIt() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> CommandSpec.of("tool").withArg("hidden\0argument"));

        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    void capturesImmutableArgumentsSnapshot() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("status");
        String[] array = {"array"};

        CommandSpec spec = CommandSpec.of("git").withArgs(arguments);
        CommandSpec fromArray = CommandSpec.of("git").withArgs(array);

        arguments.add("--short");
        array[0] = "mutated";

        assertEquals("git", spec.executable());
        assertEquals(1, spec.arguments().size());
        assertEquals("status", spec.arguments().get(0));
        assertEquals(List.of("array"), fromArray.arguments());
    }

    @Test
    void emptyBulkAppendPreservesIdentity() {
        CommandSpec original = CommandSpec.of("tool").withArg("base");

        assertSame(original, original.withArgs(List.of()));
        assertSame(original, original.withArgs(new String[0]));
    }

    @Test
    void bulkAppendSnapshotsAliasedMutableCollectionAndPreservesOrder() {
        ArrayList<String> shared = new ArrayList<>(List.of("first", "second"));
        CommandSpec original = CommandSpec.of("tool").withArgs(shared);

        CommandSpec appended = original.withArgs(shared);
        shared.set(0, "mutated");
        shared.add("late");

        assertEquals(List.of("first", "second"), original.arguments());
        assertEquals(List.of("first", "second", "first", "second"), appended.arguments());
    }

    @Test
    void bulkAppendUsesOneDefensiveSnapshotDuringConcurrentMutation() throws Exception {
        CoordinatedSnapshotCollection additions =
                new CoordinatedSnapshotCollection(List.of("snapshot-first", "snapshot-second"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CommandSpec> result =
                    executor.submit(() -> CommandSpec.of("tool").withArg("base").withArgs(additions));
            additions.awaitSnapshot();
            additions.replaceContents(List.of("mutated"));
            additions.releaseSnapshot();

            assertEquals(
                    List.of("base", "snapshot-first", "snapshot-second"),
                    result.get(2, TimeUnit.SECONDS).arguments());
            assertEquals(1, additions.snapshotCalls());
            assertEquals(0, additions.iteratorCalls());
        } finally {
            additions.releaseSnapshot();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidBulkArgumentsFailAtomicallyAtEveryPosition() {
        CommandSpec original = CommandSpec.of("tool").withArgs("base");

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
        CommandSpec shell = CommandSpec.shell("echo ready");

        assertSame(shell, shell.withArgs(List.of()));
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> shell.withArgs(List.of("unexpected")));

        assertEquals("shell commands do not accept argv arguments", failure.getMessage());
    }

    @Test
    void bulkAppendKeepsBranchesIndependent() {
        CommandSpec base = CommandSpec.of("tool").withArg("base");

        CommandSpec left = base.withArgs(List.of("left-1", "left-2"));
        CommandSpec right = base.withArgs("right-1", "right-2");

        assertNotSame(base, left);
        assertNotSame(base, right);
        assertEquals(List.of("base"), base.arguments());
        assertEquals(List.of("base", "left-1", "left-2"), left.arguments());
        assertEquals(List.of("base", "right-1", "right-2"), right.arguments());
    }

    @Test
    void largeBulkAppendTakesOneCallerSnapshotWithoutIteratingTheCallerAgain() {
        CountingSnapshotCollection additions =
                new CountingSnapshotCollection(java.util.stream.IntStream.range(0, 20_000)
                        .mapToObj(index -> "argument-" + index)
                        .toList());

        CommandSpec result = CommandSpec.of("tool").withArgs(additions);

        assertEquals(20_000, result.arguments().size());
        assertEquals("argument-0", result.arguments().get(0));
        assertEquals("argument-19999", result.arguments().get(19_999));
        assertEquals(1, additions.snapshotCalls());
        assertEquals(0, additions.iteratorCalls());
    }

    @Test
    void capturesWorkingDirectoryAndEnvironment() {
        Path workingDirectory = Path.of("project");

        CommandSpec spec = CommandSpec.of("python")
                .withWorkingDirectory(workingDirectory)
                .withEnvironment("PYTHONUTF8", "1")
                .withCleanEnvironment();

        assertEquals(workingDirectory, spec.workingDirectory().orElseThrow());
        assertEquals("1", spec.environment().get("PYTHONUTF8"));
        assertEquals(EnvironmentPolicy.CLEAN, spec.environmentPolicy());
    }

    @Test
    void comparesByValue() {
        CommandSpec left = CommandSpec.of("git")
                .withArgs("status")
                .withWorkingDirectory(Path.of("project"))
                .withEnvironment("LC_ALL", "C");
        CommandSpec right = CommandSpec.of("git")
                .withArgs("status")
                .withWorkingDirectory(Path.of("project"))
                .withEnvironment("LC_ALL", "C");

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void rejectsInvalidEnvironmentName() {
        assertThrows(
                IllegalArgumentException.class, () -> CommandSpec.of("tool").withEnvironment("BAD=NAME", "value"));
    }

    @Test
    void rejectsInvalidEnvironmentValueWithoutEchoingIt() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> CommandSpec.of("tool")
                .withEnvironment("SECRET", "hidden\0value"));

        assertFalse(exception.getMessage().contains("hidden"));
    }

    private static final class CoordinatedSnapshotCollection extends AbstractCollection<String> {

        private final CountDownLatch snapshotTaken = new CountDownLatch(1);
        private final CountDownLatch releaseSnapshot = new CountDownLatch(1);
        private final AtomicInteger snapshotCalls = new AtomicInteger();
        private final AtomicInteger iteratorCalls = new AtomicInteger();
        private List<String> values;

        private CoordinatedSnapshotCollection(List<String> values) {
            this.values = new ArrayList<>(values);
        }

        @Override
        public synchronized Iterator<String> iterator() {
            iteratorCalls.incrementAndGet();
            return List.copyOf(values).iterator();
        }

        @Override
        public synchronized int size() {
            return values.size();
        }

        @Override
        public Object[] toArray() {
            snapshotCalls.incrementAndGet();
            Object[] snapshot;
            synchronized (this) {
                snapshot = values.toArray();
            }
            snapshotTaken.countDown();
            await(releaseSnapshot);
            return snapshot;
        }

        private void awaitSnapshot() throws InterruptedException {
            if (!snapshotTaken.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("argument snapshot did not start");
            }
        }

        private synchronized void replaceContents(List<String> replacement) {
            values = new ArrayList<>(replacement);
        }

        private void releaseSnapshot() {
            releaseSnapshot.countDown();
        }

        private int snapshotCalls() {
            return snapshotCalls.get();
        }

        private int iteratorCalls() {
            return iteratorCalls.get();
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("concurrent snapshot mutation did not finish");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while coordinating argument snapshot", exception);
            }
        }
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
