package com.github.ulviar.icli.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class PublicDocumentationCoverageTest {

    private static final Path PUBLIC_DOCS = Path.of("docs");
    private static final Path PUBLIC_SCENARIOS = Path.of("docs/scenarios/index.md");
    private static final List<Path> COMPILE_TESTED_EXAMPLE_SOURCES = List.of(
            Path.of("src/test/java/com/github/ulviar/icli/examples/CommandServiceApiExamples.java"),
            Path.of(
                    "icli-integrations/src/test/java/com/github/ulviar/icli/integration/examples/CommandBackedToolExamples.java"));

    @Test
    void publicScenarioDocsReferenceEveryCompileTestedCoreExample() throws Exception {
        String scenarios = Files.readString(PUBLIC_SCENARIOS, StandardCharsets.UTF_8);

        for (String methodName : coreExampleMethods()) {
            assertTrue(
                    scenarios.contains("`" + methodName + "`"),
                    () -> "public scenario docs must reference compile-tested core example `" + methodName + "`");
        }
    }

    @Test
    void publicCoreExampleReferencesResolveToCompileTestedMethods() throws Exception {
        Set<String> methodNames = coreExampleMethods();

        for (String methodName : documentedCoreExampleReferences()) {
            assertTrue(
                    methodNames.contains(methodName),
                    () -> "public docs reference non-compiled core example `" + methodName + "`");
        }
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

    private static Set<String> coreExampleMethods() {
        return Arrays.stream(CommandServiceApiExamples.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> documentedCoreExampleReferences() throws Exception {
        LinkedHashSet<String> names = new LinkedHashSet<>();
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
        return Set.copyOf(names);
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
