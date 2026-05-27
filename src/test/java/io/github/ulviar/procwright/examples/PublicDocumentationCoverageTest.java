package io.github.ulviar.procwright.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PublicDocumentationCoverageTest {

    private static final Path PUBLIC_DOCS = Path.of("docs");
    private static final Path PUBLIC_SCENARIOS = Path.of("docs/scenarios/index.md");
    private static final List<Path> COMPILE_TESTED_EXAMPLE_SOURCES = List.of(
            Path.of("src/test/java/io/github/ulviar/procwright/examples/GettingStartedExample.java"),
            Path.of("src/test/java/io/github/ulviar/procwright/examples/ReferenceApiExamples.java"),
            Path.of("src/test/java/io/github/ulviar/procwright/examples/CommandServiceApiExamples.java"),
            Path.of(
                    "procwright-integrations/src/test/java/io/github/ulviar/procwright/integration/examples/CommandBackedToolExamples.java"));

    @Test
    void publicScenarioDocsLinkToUserTaskGuides() throws Exception {
        String scenarios = Files.readString(PUBLIC_SCENARIOS, StandardCharsets.UTF_8);

        for (String guide : scenarioUserGuides()) {
            assertTrue(scenarios.contains(guide), () -> "public scenario docs must link user guide `" + guide + "`");
        }
    }

    @Test
    void publicDocsDoNotExposeCompileTestMethodNamesAsNavigation() throws Exception {
        assertTrue(
                documentedCoreExampleMethodReferences().isEmpty(),
                "public docs should navigate by user tasks, not by compile-tested example method names");
    }

    @Test
    void publicJavaSnippetsAreBackedByCompileTestedExampleSources() throws Exception {
        List<String> sourceSnapshots = compileTestedExampleSourceSnapshots();

        for (Snippet snippet : publicJavaSnippets()) {
            String canonicalSnippet = canonical(snippet.code());
            assertTrue(
                    sourceSnapshots.stream().anyMatch(source -> source.contains(canonicalSnippet)),
                    () -> "public Java snippet in "
                            + snippet.path()
                            + ":"
                            + snippet.line()
                            + " must be copied from a compile-tested example source");
        }
    }

    @Test
    void publicScenarioPagesContainConcreteCodeExamples() throws Exception {
        try (var paths = Files.list(Path.of("docs/scenarios"))) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> !path.getFileName().toString().equals("index.md"))
                    .toList()) {
                String text = Files.readString(path, StandardCharsets.UTF_8);

                assertTrue(
                        text.contains("```java") || text.contains("```kotlin"),
                        () -> "scenario page " + path + " must contain a concrete code example");
            }
        }
    }

    @Test
    void publicApiIndexLinksToDiscoverableApiReferences() throws Exception {
        String apiIndex = Files.readString(Path.of("docs/api/index.md"), StandardCharsets.UTF_8);

        assertTrue(apiIndex.contains("https://ulviar.github.io/Procwright/api/java/core/"));
        assertTrue(apiIndex.contains("https://ulviar.github.io/Procwright/api/java/integrations/"));
        assertTrue(apiIndex.contains("../reference/kotlin-api.md"));
    }

    @Test
    void docsAndContextDoNotKeepObsoleteProjectHistoryReferences() throws Exception {
        for (Path path : documentationAndContextFiles()) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            for (String forbidden : obsoleteProjectHistoryReferences()) {
                assertTrue(
                        !text.contains(forbidden),
                        () -> "obsolete project-history reference `" + forbidden + "` must be removed from " + path);
            }
        }
    }

    @Test
    void docsAndContextDoNotUseRetiredPublicationCoordinates() throws Exception {
        for (Path path : documentationAndContextFiles()) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            for (String forbidden : retiredPublicationReferences()) {
                assertTrue(
                        !text.contains(forbidden),
                        () -> "retired publication reference `" + forbidden + "` must be removed from " + path);
            }
        }
    }

    private static List<String> scenarioUserGuides() {
        return List.of(
                "../how-to/run-finite-command.md",
                "../how-to/choose-process-scenario.md#prompt-driven-installer-or-configurator",
                "../how-to/automate-prompts.md",
                "../how-to/talk-to-line-worker.md",
                "../how-to/choose-process-scenario.md#framed-or-typed-protocol-worker",
                "../how-to/follow-logs.md",
                "../how-to/reuse-workers.md",
                "../how-to/require-terminal.md",
                "../examples.md",
                "../how-to/wrap-cli-tool.md");
    }

    private static List<String> documentedCoreExampleMethodReferences() throws Exception {
        ArrayList<String> names = new ArrayList<>();
        try (var paths = Files.walk(PUBLIC_DOCS)) {
            for (Path path :
                    paths.filter(path -> path.toString().endsWith(".md")).toList()) {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                var matcher = java.util.regex.Pattern.compile("CommandServiceApiExamples\\.([A-Za-z0-9_]+)")
                        .matcher(text);
                while (matcher.find()) {
                    String methodName = matcher.group(1);
                    if (!methodName.equals("java")) {
                        names.add(methodName);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    private static List<String> compileTestedExampleSourceSnapshots() throws Exception {
        ArrayList<String> sources = new ArrayList<>();
        for (Path source : COMPILE_TESTED_EXAMPLE_SOURCES) {
            sources.add(canonical(Files.readString(source, StandardCharsets.UTF_8)));
        }
        return List.copyOf(sources);
    }

    private static List<Snippet> publicJavaSnippets() throws Exception {
        ArrayList<Snippet> snippets = new ArrayList<>();
        try (var paths = Files.walk(PUBLIC_DOCS)) {
            for (Path path :
                    paths.filter(path -> path.toString().endsWith(".md")).toList()) {
                collectJavaSnippets(path, snippets);
            }
        }
        return List.copyOf(snippets);
    }

    private static List<Path> documentationAndContextFiles() throws Exception {
        ArrayList<Path> paths = new ArrayList<>();
        for (Path root : List.of(Path.of("docs"), Path.of("context"))) {
            try (var tree = Files.walk(root)) {
                paths.addAll(tree.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".md"))
                        .toList());
            }
        }
        paths.add(Path.of("README.md"));
        paths.add(Path.of("AGENTS.md"));
        return List.copyOf(paths);
    }

    private static List<String> obsoleteProjectHistoryReferences() {
        return List.of(
                "legacy-lessons",
                "clean rewrite",
                "clean-rewrite",
                "старый проект",
                "старого проекта",
                "старая версия",
                "старой версии",
                "старую реализацию",
                "89c8be");
    }

    private static List<String> retiredPublicationReferences() {
        String retiredGroup = String.join(".", "com", "github", "ulviar");
        String retiredRepository = String.join("", "maven.pkg.", "github.com", "/Ulviar/Procwright");
        return List.of(
                retiredGroup + ":procwright",
                retiredGroup + ":procwright-kotlin",
                retiredGroup + ":procwright-integrations",
                retiredRepository);
    }

    private static void collectJavaSnippets(Path path, List<Snippet> snippets) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        boolean inJavaFence = false;
        int startLine = -1;
        StringBuilder code = new StringBuilder();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!inJavaFence && line.equals("```java")) {
                inJavaFence = true;
                startLine = index + 2;
                code.setLength(0);
                continue;
            }
            if (inJavaFence && line.equals("```")) {
                snippets.add(new Snippet(path, startLine, code.toString()));
                inJavaFence = false;
                continue;
            }
            if (inJavaFence) {
                code.append(line).append('\n');
            }
        }
    }

    private static String canonical(String text) {
        return text.replaceAll("\\s+", "");
    }

    private record Snippet(Path path, int line, String code) {}
}
