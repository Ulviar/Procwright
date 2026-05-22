package com.github.ulviar.icli.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class ScenarioCookbookCoverageTest {

    private static final Path COOKBOOK = Path.of("context/scenario-cookbook.md");

    @Test
    void cookbookReferencesEveryCompileTestedCoreExample() throws Exception {
        String cookbook = Files.readString(COOKBOOK, StandardCharsets.UTF_8);

        for (String methodName : coreExampleMethods()) {
            assertTrue(
                    cookbook.contains("`" + methodName + "`"),
                    () -> "scenario cookbook must reference compile-tested core example `" + methodName + "`");
        }
    }

    @Test
    void cookbookCoreExampleReferencesResolveToCompileTestedMethods() throws Exception {
        String cookbook = Files.readString(COOKBOOK, StandardCharsets.UTF_8);
        Set<String> methodNames = coreExampleMethods();

        for (String methodName : cookbookExampleMethods(cookbook, "## `run`", "## CLI-backed integrations")) {
            assertTrue(
                    methodNames.contains(methodName),
                    () -> "scenario cookbook references non-compiled core example `" + methodName + "`");
        }
        for (String methodName : cookbookSelectionMapCoreExamples(cookbook)) {
            assertTrue(
                    methodNames.contains(methodName),
                    () -> "scenario cookbook map references non-compiled core example `" + methodName + "`");
        }
    }

    @Test
    void cookbookContainsCanonicalScenarioSections() throws Exception {
        String cookbook = Files.readString(COOKBOOK, StandardCharsets.UTF_8);

        for (String section : Set.of(
                "## `run`",
                "## `interactive`",
                "## `Expect`",
                "## `lineSession`",
                "## `protocolSession`",
                "## `listen`",
                "## Nested pooled scenarios",
                "## Диагностика",
                "## Сценарные presets",
                "## CLI-backed integrations",
                "## Релизный gate")) {
            assertTrue(cookbook.contains(section), () -> "scenario cookbook must contain section " + section);
        }
    }

    private static Set<String> coreExampleMethods() {
        return Arrays.stream(CommandServiceApiExamples.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    static Set<String> cookbookExampleMethods(String cookbook, String startMarker, String endMarker) {
        int start = cookbook.indexOf(startMarker);
        int end = cookbook.indexOf(endMarker);
        if (start < 0 || end < 0 || end < start) {
            throw new AssertionError("missing cookbook section bounded by " + startMarker + " and " + endMarker);
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        boolean capturing = false;
        for (String line : cookbook.substring(start, end).lines().toList()) {
            if (line.startsWith("Compile-tested example")) {
                capturing = true;
                continue;
            }
            if (!capturing) {
                continue;
            }
            if (line.startsWith("- `")) {
                int startTick = line.indexOf('`');
                int endTick = line.indexOf('`', startTick + 1);
                names.add(line.substring(startTick + 1, endTick));
                continue;
            }
            if (!line.isBlank()) {
                capturing = false;
            }
        }
        return Set.copyOf(names);
    }

    private static Set<String> cookbookSelectionMapCoreExamples(String cookbook) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        boolean inMap = false;
        for (String line : cookbook.lines().toList()) {
            if (line.equals("## Карта выбора")) {
                inMap = true;
                continue;
            }
            if (inMap && line.startsWith("## `run`")) {
                break;
            }
            if (!inMap || !line.startsWith("|") || line.contains("---")) {
                continue;
            }
            String[] columns = line.split("\\|");
            if (columns.length < 4 || columns[3].contains("Compile-tested example")) {
                continue;
            }
            if (columns[2].contains(":icli-integrations")
                    || columns[2].contains("JsonLineSession")
                    || columns[2].contains("CancellableCall")
                    || columns[2].contains("ContentLengthJsonFrames")) {
                continue;
            }
            names.add(columns[3].trim().replace("`", ""));
        }
        return Set.copyOf(names);
    }
}
