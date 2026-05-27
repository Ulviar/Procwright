package io.github.ulviar.procwright.integration.examples;

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

final class PublicIntegrationDocumentationCoverageTest {

    private static final Path PUBLIC_DOCS = Path.of("../docs");
    private static final Path CORE_DOCUMENTATION_TEST =
            Path.of("../src/test/java/io/github/ulviar/procwright/examples/PublicDocumentationCoverageTest.java");

    @Test
    void publicIntegrationExampleReferencesResolveToCompileTestedMethods() throws Exception {
        Set<String> methodNames = integrationExampleMethods();

        for (String methodName : documentedIntegrationExampleReferences()) {
            assertTrue(
                    methodNames.contains(methodName),
                    () -> "public docs reference non-compiled integration example `" + methodName + "`");
        }
    }

    @Test
    void coreDocumentationTestOwnsPublicJavaSnippetCoverage() {
        assertTrue(
                Files.isRegularFile(CORE_DOCUMENTATION_TEST),
                "root module public documentation test owns Java snippet source-backed coverage");
    }

    private static Set<String> integrationExampleMethods() {
        return Arrays.stream(CommandBackedToolExamples.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> documentedIntegrationExampleReferences() throws Exception {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try (var paths = Files.walk(PUBLIC_DOCS)) {
            for (Path path :
                    paths.filter(path -> path.toString().endsWith(".md")).toList()) {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                var matcher = java.util.regex.Pattern.compile("CommandBackedToolExamples\\.([A-Za-z0-9_]+)")
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
}
