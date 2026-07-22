/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.consumer.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PoolLifecycleExamplesContractTest {

    private static final Path REPOSITORY_ROOT = repositoryRoot();

    @Test
    void javaPoolExamplesUseTryWithResources() throws Exception {
        assertJavaPoolLifecycle(read("docs/examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java"));
        assertJavaPoolLifecycle(
                read("docs/examples/java/io/github/ulviar/procwright/examples/ProtocolPoolExample.java"));

        String drainTimeout =
                read("docs/examples/java/io/github/ulviar/procwright/examples/PoolDrainTimeoutExample.java");
        assertTrue(drainTimeout.indexOf("PooledLineSession pool = draft.open();") < drainTimeout.indexOf("try (pool)"));
        assertTrue(drainTimeout.indexOf("try (pool)") < drainTimeout.indexOf("finally"));
        assertTrue(drainTimeout.indexOf("finally") < drainTimeout.indexOf("pool.closeAsync()"));
        assertFalse(drainTimeout.contains("catch (PooledLineSessionException"));

        String consumerExamples = read(
                "procwright-consumer-examples/src/main/java/io/github/ulviar/procwright/consumer/examples/ConsumerScenarios.java");
        assertTrue(occurrences(consumerExamples, "try (Pooled") == 2);
        assertFalse(consumerExamples.contains("PoolCleanup"));

        String apiExamples = read("src/test/java/io/github/ulviar/procwright/examples/CommandServiceApiExamples.java");
        assertTrue(occurrences(apiExamples, "try (Pooled") == 3);
        assertFalse(apiExamples.contains("closePoolAndAwait"));

        assertComparisonHarnessLifecycle(
                read(
                        "procwright-comparison/src/main/java/io/github/ulviar/procwright/comparison/ProcwrightCandidateAdapter.java"));
        assertComparisonHarnessLifecycle(
                read(
                        "procwright-comparison/src/main/java/io/github/ulviar/procwright/comparison/StressComparisonRunner.java"));
    }

    @Test
    void kotlinPoolExampleUsesUse() throws Exception {
        String source = read("docs/examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinPoolExample.kt");

        assertTrue(source.contains(".use { pool ->"));
        assertFalse(source.contains("awaitDrained"));
        assertFalse(source.contains("primaryFailure"));
    }

    @Test
    void publicLifecycleTextDistinguishesPoolsFromSynchronousSessionClose() throws Exception {
        String readme = read("README.md");
        String examples = read("docs/examples.md");
        String gettingStarted = read("docs/getting-started.md");
        String scenario = read("docs/scenarios/pooling.md");
        String howTo = read("docs/how-to/reuse-workers.md");

        assertTrue(readme.contains("Open sessions and pools with try-with-resources"));
        assertTrue(readme.contains("Pool `close()` waits for bounded worker drain"));
        assertFalse(examples.contains("PoolCleanup.java"));
        assertTrue(gettingStarted.contains("Sessions and pools own processes"));
        assertFalse(gettingStarted.contains("Pools require explicit bounded cleanup"));
        assertTrue(scenario.contains("`close()` is a bounded synchronous close-and-drain"));
        assertTrue(howTo.contains("`close()` is a bounded synchronous close-and-drain"));
        assertTrue(scenario.contains("`closeAsync()`"));
        assertTrue(howTo.contains("`closeAsync()`"));
    }

    private static void assertJavaPoolLifecycle(String source) {
        assertTrue(source.contains("try (Pooled"));
        assertFalse(source.contains("awaitDrained"));
        assertFalse(source.contains("PoolCleanup"));
    }

    private static void assertComparisonHarnessLifecycle(String source) {
        assertTrue(source.contains("try (Pooled"));
        assertFalse(source.contains("closePoolAndAwait"));
    }

    private static String read(String relativePath) throws Exception {
        return Files.readString(REPOSITORY_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String token) {
        return source.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("README.md"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("README.md"))) {
            return parent;
        }
        throw new IllegalStateException("Could not locate repository root from " + current);
    }
}
