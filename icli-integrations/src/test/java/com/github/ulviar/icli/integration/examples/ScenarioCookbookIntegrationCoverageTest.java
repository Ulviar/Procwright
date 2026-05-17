package com.github.ulviar.icli.integration.examples;

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

final class ScenarioCookbookIntegrationCoverageTest {

    private static final Path COOKBOOK = Path.of("../context/scenario-cookbook.md");

    @Test
    void cookbookReferencesEveryCompileTestedIntegrationExample() throws Exception {
        String cookbook = Files.readString(COOKBOOK, StandardCharsets.UTF_8);

        for (String methodName : integrationExampleMethods()) {
            assertTrue(
                    cookbook.contains("`" + methodName + "`"),
                    () -> "scenario cookbook must reference compile-tested integration example `" + methodName + "`");
        }
    }

    @Test
    void cookbookIntegrationExampleReferencesResolveToCompileTestedMethods() throws Exception {
        String cookbook = Files.readString(COOKBOOK, StandardCharsets.UTF_8);
        Set<String> methodNames = integrationExampleMethods();

        for (String methodName : integrationCookbookExampleMethods(cookbook)) {
            assertTrue(
                    methodNames.contains(methodName),
                    () -> "scenario cookbook references non-compiled integration example `" + methodName + "`");
        }
        for (String methodName : cookbookSelectionMapIntegrationExamples(cookbook)) {
            assertTrue(
                    methodNames.contains(methodName),
                    () -> "scenario cookbook map references non-compiled integration example `" + methodName + "`");
        }
    }

    private static Set<String> integrationExampleMethods() {
        return Arrays.stream(CommandBackedToolExamples.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> integrationCookbookExampleMethods(String cookbook) {
        int start = cookbook.indexOf("## CLI-backed integrations");
        int end = cookbook.indexOf("## Релизный gate");
        if (start < 0 || end < 0 || end < start) {
            throw new AssertionError("missing integration cookbook section");
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

    private static Set<String> cookbookSelectionMapIntegrationExamples(String cookbook) {
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
                names.add(columns[3].trim().replace("`", ""));
            }
        }
        return Set.copyOf(names);
    }
}
