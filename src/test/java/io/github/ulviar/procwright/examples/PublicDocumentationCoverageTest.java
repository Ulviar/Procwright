/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.InteractiveScenario;
import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.ProtocolSessionScenario;
import io.github.ulviar.procwright.RunScenario;
import io.github.ulviar.procwright.StreamScenario;
import io.github.ulviar.procwright.terminal.PtyProvider;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class PublicDocumentationCoverageTest {

    private static final Path DOCS = Path.of("docs");
    private static final Pattern FENCED_CODE = Pattern.compile("(?ms)^```(?:java|kotlin)\\R(.*?)^```[ \\t]*$");
    private static final Pattern SNIPPET_INCLUDE = Pattern.compile("--8<-- \\\"([^\\\"]+)\\\"");
    private static final Pattern SNIPPET_FENCE =
            Pattern.compile("\\A[ \\t]*--8<-- \\\"([^\\\"]+)\\\"[ \\t]*(?:\\R)?\\z");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("(?<!!)\\[[^]]+]\\(([^)]+)\\)");
    private static final Pattern CORE_EXAMPLE = Pattern.compile(
            "(?ms)^<!-- procwright-example: (examples/java/[^\\r\\n]+\\.java) -->\\R" + "```java\\R(.*?)^```[ \\t]*$");
    private static final Pattern CORE_EXAMPLE_MARKER =
            Pattern.compile("(?m)^<!-- procwright-example: examples/java/[^\\r\\n]+\\.java -->\\R\\z");
    private static final Pattern CALLBACK_API_REFERENCE =
            Pattern.compile("`((?:CommandService|RunScenario\\.Draft|InteractiveScenario\\.Draft|"
                    + "LineSessionScenario\\.(?:Draft|PoolDraft)|ProtocolSessionScenario\\.(?:Draft|PoolDraft)|"
                    + "StreamScenario\\.Draft)\\.[A-Za-z][A-Za-z0-9]*)`");
    private static final String CALLBACK_POLICY_HEADING = "## Persistent callback concurrency";
    private static final Pattern BUILD_CONFIGURATION_MARKER =
            Pattern.compile("(?m)^<!-- procwright-docs: build-configuration -->\\R\\z");
    private static final List<Class<?>> CALLBACK_API_OWNERS = List.of(
            CommandService.class,
            RunScenario.Draft.class,
            InteractiveScenario.Draft.class,
            LineSessionScenario.Draft.class,
            LineSessionScenario.PoolDraft.class,
            ProtocolSessionScenario.Draft.class,
            ProtocolSessionScenario.PoolDraft.class,
            StreamScenario.Draft.class);

    @Test
    void everyScenarioAndTaskPageShowsCompileTestedCode() throws Exception {
        for (Path directory : List.of(Path.of("docs/scenarios"), Path.of("docs/how-to"))) {
            try (var files = Files.list(directory)) {
                for (Path page : files.filter(path -> path.toString().endsWith(".md"))
                        .filter(path -> !path.getFileName().toString().equals("index.md"))
                        .toList()) {
                    String text = read(page);
                    assertTrue(
                            CORE_EXAMPLE.matcher(text).find() || containsCompiledSnippet(text),
                            () -> page + " must show a compile-tested canonical source");
                }
            }
        }
    }

    @Test
    void canonicalExamplesMatchCompiledSources() throws Exception {
        for (Path page : publicMarkdownFiles()) {
            String text = read(page);
            assertSnippetIncludesAreCompiledSources(page, text);
            Matcher examples = CORE_EXAMPLE.matcher(text);
            while (examples.find()) {
                Path source = DOCS.resolve(examples.group(1)).normalize();
                assertTrue(
                        source.startsWith(DOCS.resolve("examples/java")),
                        () -> page + " example escapes the compiled Java sources: " + examples.group(1));
                assertTrue(Files.isRegularFile(source), () -> page + " example is missing: " + examples.group(1));
                assertEquals(read(source), examples.group(2), () -> page + " example drifted from " + source);
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
                        isCompiledSnippetFence(fences.group(1))
                                || CORE_EXAMPLE_MARKER.matcher(prefix).find()
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

    @Test
    void callbackPolicyInventoriesEveryPublicCallbackEntryPoint() throws Exception {
        Set<String> callbackApi = new TreeSet<>();
        for (Class<?> owner : CALLBACK_API_OWNERS) {
            Arrays.stream(owner.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> Arrays.stream(method.getParameterTypes())
                            .anyMatch(PublicDocumentationCoverageTest::isCallbackType))
                    .map(method -> publicTypeName(owner) + "." + method.getName())
                    .forEach(callbackApi::add);
        }

        Set<String> documentedApi = new TreeSet<>();
        String policy = read(Path.of("docs/reference/policies.md"));
        int sectionStart = policy.indexOf(CALLBACK_POLICY_HEADING);
        assertTrue(sectionStart >= 0, "callback concurrency policy is missing");
        int sectionEnd = policy.indexOf("\n## ", sectionStart + CALLBACK_POLICY_HEADING.length());
        String callbackSection =
                sectionEnd < 0 ? policy.substring(sectionStart) : policy.substring(sectionStart, sectionEnd);
        Matcher references = CALLBACK_API_REFERENCE.matcher(callbackSection);
        while (references.find()) {
            documentedApi.add(references.group(1));
        }

        assertEquals(callbackApi, documentedApi);
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

    private static boolean containsCompiledSnippet(String text) {
        Matcher includes = SNIPPET_INCLUDE.matcher(text);
        while (includes.find()) {
            if (isCompiledExamplePath(Path.of(includes.group(1)).normalize())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCompiledSnippetFence(String body) {
        Matcher include = SNIPPET_FENCE.matcher(body);
        return include.matches()
                && isCompiledExamplePath(Path.of(include.group(1)).normalize());
    }

    private static void assertSnippetIncludesAreCompiledSources(Path page, String text) {
        Matcher includes = SNIPPET_INCLUDE.matcher(text);
        while (includes.find()) {
            Path relativeSource = Path.of(includes.group(1)).normalize();
            assertTrue(
                    isCompiledExamplePath(relativeSource),
                    () -> page + " includes a source outside the compiled examples: " + includes.group(1));
            assertTrue(
                    Files.isRegularFile(DOCS.resolve(relativeSource)),
                    () -> page + " includes a missing source: " + includes.group(1));
        }
    }

    private static boolean isCompiledExamplePath(Path path) {
        String name = path.getFileName().toString();
        return (path.startsWith(Path.of("examples/java")) || path.startsWith(Path.of("examples/integrations")))
                        && name.endsWith(".java")
                || path.startsWith(Path.of("examples/kotlin")) && name.endsWith(".kt");
    }

    private static boolean isCallbackType(Class<?> type) {
        return type == PtyProvider.class || type.isAnnotationPresent(FunctionalInterface.class);
    }

    private static String publicTypeName(Class<?> type) {
        return type.getCanonicalName().substring("io.github.ulviar.procwright.".length());
    }

    private static Path example(String fileName) {
        return Path.of("docs/examples/java/io/github/ulviar/procwright/examples")
                .resolve(fileName);
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
