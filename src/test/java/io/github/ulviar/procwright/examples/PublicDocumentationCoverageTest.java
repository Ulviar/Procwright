/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class PublicDocumentationCoverageTest {

    private static final Path DOCS = Path.of("docs");
    private static final Pattern FENCED_CODE = Pattern.compile("(?ms)^```(?:java|kotlin)\\R(.*?)^```[ \\t]*$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("(?<!!)\\[[^]]+]\\(([^)]+)\\)");
    private static final String EXAMPLE_SOURCE = "examples/(?:java|integrations|kotlin)/[^\\r\\n#]+\\.(?:java|kt)";
    private static final Pattern SOURCE_EXAMPLE = Pattern.compile("(?ms)^<!-- procwright-example: (" + EXAMPLE_SOURCE
            + ")(?:#([a-z][a-z0-9-]*))? -->\\R" + "```(java|kotlin)\\R(.*?)^```[ \\t]*$");
    private static final Pattern SOURCE_EXAMPLE_MARKER =
            Pattern.compile("(?m)^<!-- procwright-example: " + EXAMPLE_SOURCE + "(?:#[a-z][a-z0-9-]*)? -->\\R\\z");
    private static final Pattern BUILD_CONFIGURATION_MARKER =
            Pattern.compile("(?m)^<!-- procwright-docs: build-configuration -->\\R\\z");

    @Test
    void canonicalExamplesMatchCompiledSources() throws Exception {
        for (Path page : publicMarkdownFiles()) {
            String text = read(page);
            assertTrue(!text.contains("--8<--"), () -> page + " must also show its code when read on GitHub");
            Matcher examples = SOURCE_EXAMPLE.matcher(text);
            while (examples.find()) {
                Path relativeSource = Path.of(examples.group(1)).normalize();
                assertTrue(isCompiledExamplePath(relativeSource), () -> page + " example must use compiled sources");
                Path source = DOCS.resolve(relativeSource);
                assertTrue(Files.isRegularFile(source), () -> page + " example is missing: " + examples.group(1));
                assertEquals(source.toString().endsWith(".java") ? "java" : "kotlin", examples.group(3));
                String expected =
                        examples.group(2) == null ? read(source) : extractRegion(read(source), examples.group(2));
                assertEquals(expected, examples.group(4), () -> page + " example drifted from " + source);
            }
        }
    }

    @Test
    void everyJavaAndKotlinFenceDeclaresItsProvenance() throws Exception {
        for (Path page : publicMarkdownFiles()) {
            String text = read(page);
            Matcher fences = FENCED_CODE.matcher(text);
            while (fences.find()) {
                String prefix = text.substring(0, fences.start());
                assertTrue(
                        SOURCE_EXAMPLE_MARKER.matcher(prefix).find()
                                || BUILD_CONFIGURATION_MARKER.matcher(prefix).find(),
                        () -> page + " contains Java or Kotlin code without a compiled source or configuration marker");
            }
        }
    }

    @Test
    void poolExamplesShowScopedOwnership() throws Exception {
        assertTrue(read(example("LinePoolExample.java")).contains("try (PooledLineSession pool = "));
        assertTrue(read(example("ProtocolPoolExample.java")).contains("try (PooledProtocolSession<"));
        String closeTimeoutExample = read(example("PoolDrainTimeoutExample.java"));
        int resourceScope = closeTimeoutExample.indexOf("try (pool)");
        int finallyBlock = closeTimeoutExample.indexOf("finally", resourceScope);
        int cleanupObserver = closeTimeoutExample.indexOf("pool.closeAsync().whenComplete(", finallyBlock);
        assertTrue(resourceScope >= 0 && finallyBlock > resourceScope && cleanupObserver > finallyBlock);
        assertTrue(
                read(Path.of("docs/examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinPoolExample.kt"))
                        .contains(".use { pool ->"));
    }

    @Test
    void readmeLocalLinksResolve() throws Exception {
        Matcher links = MARKDOWN_LINK.matcher(read(Path.of("README.md")));
        while (links.find()) {
            String destination = links.group(1);
            if (destination.contains("://") || destination.startsWith("#")) {
                continue;
            }

            Path target = Path.of(destination.split("#", 2)[0]).normalize();
            assertTrue(Files.exists(target), () -> "README link does not resolve: " + destination);
        }
    }

    private static List<Path> markdownFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static List<Path> publicMarkdownFiles() throws Exception {
        List<Path> pages = new ArrayList<>(markdownFiles(DOCS));
        pages.add(Path.of("README.md"));
        return pages;
    }

    private static String extractRegion(String source, String region) {
        Matcher start = Pattern.compile("(?m)^\\h*// docs:start " + Pattern.quote(region) + "\\R")
                .matcher(source);
        Matcher end = Pattern.compile("(?m)^\\h*// docs:end " + Pattern.quote(region) + "\\h*$")
                .matcher(source);
        assertTrue(start.find(), () -> "Missing example region start: " + region);
        assertTrue(end.find(), () -> "Missing example region end: " + region);
        int from = start.end();
        int to = end.start();
        assertTrue(
                from < to && !start.find() && !end.find(),
                () -> "Example region must be unique and nonempty: " + region);
        return source.substring(from, to).stripTrailing().stripIndent() + "\n";
    }

    @Test
    void namedRegionKeepsCodeAndRemovesOnlyItsCommonIndent() {
        String source =
                "class Demo {\n    // docs:start run\n    if (ready) {\n        execute();\n    }\n    // docs:end run\n}\n";
        assertEquals("if (ready) {\n    execute();\n}\n", extractRegion(source, "run"));
    }

    @Test
    void missingRepeatedOrReversedRegionMarkersCannotApproveASnippet() {
        assertThrows(AssertionError.class, () -> extractRegion("execute();\n", "run"));
        assertThrows(AssertionError.class, () -> extractRegion("// docs:start run\nexecute();\n", "run"));
        String region = "// docs:start run\nexecute();\n// docs:end run\n";
        assertThrows(AssertionError.class, () -> extractRegion(region + region, "run"));
        assertThrows(
                AssertionError.class, () -> extractRegion("// docs:end run\nexecute();\n// docs:start run\n", "run"));
    }

    private static boolean isCompiledExamplePath(Path path) {
        String name = path.getFileName().toString();
        return (path.startsWith(Path.of("examples/java")) || path.startsWith(Path.of("examples/integrations")))
                        && name.endsWith(".java")
                || path.startsWith(Path.of("examples/kotlin")) && name.endsWith(".kt");
    }

    private static Path example(String fileName) {
        return Path.of("docs/examples/java/io/github/ulviar/procwright/examples")
                .resolve(fileName);
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
