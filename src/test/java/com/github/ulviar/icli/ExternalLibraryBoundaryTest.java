package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ExternalLibraryBoundaryTest {

    private static final List<String> COMPARISON_DEPENDENCIES = List.of(
            "org.apache.commons:commons-exec",
            "org.zeroturnaround:zt-exec",
            "com.zaxxer:nuprocess",
            "org.jetbrains.pty4j:pty4j",
            "net.sf.expectit:expectit-core");

    private static final List<String> JMH_DEPENDENCIES =
            List.of("org.openjdk.jmh:jmh-core", "org.openjdk.jmh:jmh-generator-annprocess");

    private static final Map<String, String> FORBIDDEN_IMPORTS = Map.of(
            "org.apache.commons.exec", "Apache Commons Exec",
            "org.zeroturnaround.exec", "ZeroTurnaround zt-exec",
            "com.zaxxer.nuprocess", "NuProcess",
            "com.pty4j", "Pty4J",
            "net.sf.expectit", "ExpectIt");

    @Test
    void comparisonDependenciesRemainInComparisonModuleOnly() throws Exception {
        Path root = repositoryRoot();
        for (Path buildFile : gradleBuildFiles(root)) {
            String text = Files.readString(buildFile, StandardCharsets.UTF_8);
            boolean comparisonBuild = root.relativize(buildFile).toString().equals("icli-comparison/build.gradle.kts");
            for (String dependency : COMPARISON_DEPENDENCIES) {
                if (comparisonBuild) {
                    assertTrue(
                            containsDependencyDeclaration(text, dependency),
                            () -> "Comparison module must keep declared research dependency " + dependency);
                } else {
                    assertTrue(
                            !containsProjectDependencyDeclaration(text),
                            () -> "Public artifact build file must not depend on :icli-comparison: "
                                    + root.relativize(buildFile));
                    assertTrue(
                            !containsDependencyDeclaration(text, dependency),
                            () -> "External process-library dependency "
                                    + dependency
                                    + " leaked into "
                                    + root.relativize(buildFile));
                }
            }
            for (String dependency : JMH_DEPENDENCIES) {
                if (comparisonBuild) {
                    assertTrue(
                            containsJmhDependencyDeclaration(text, dependency),
                            () -> "Comparison module must declare JMH dependency in JMH-only configuration "
                                    + dependency);
                    assertTrue(
                            !containsDependencyDeclaration(text, dependency),
                            () -> "JMH dependency must not enter comparison main source set: " + dependency);
                } else {
                    assertTrue(
                            !containsDependencyDeclaration(text, dependency)
                                    && !containsJmhDependencyDeclaration(text, dependency),
                            () -> "JMH dependency leaked outside comparison benchmark source set: "
                                    + root.relativize(buildFile));
                }
            }
        }
    }

    private static boolean containsDependencyDeclaration(String text, String dependency) {
        return text.contains("api(\"" + dependency)
                || text.contains("implementation(\"" + dependency)
                || text.contains("runtimeOnly(\"" + dependency)
                || text.contains("compileOnly(\"" + dependency)
                || text.contains("testImplementation(\"" + dependency)
                || text.contains("testRuntimeOnly(\"" + dependency);
    }

    private static boolean containsJmhDependencyDeclaration(String text, String dependency) {
        return text.contains("\"jmhImplementation\"(\"" + dependency)
                || text.contains("\"jmhAnnotationProcessor\"(\"" + dependency);
    }

    private static boolean containsProjectDependencyDeclaration(String text) {
        return text.contains("api(project(\":icli-comparison\"")
                || text.contains("implementation(project(\":icli-comparison\"")
                || text.contains("runtimeOnly(project(\":icli-comparison\"")
                || text.contains("compileOnly(project(\":icli-comparison\"")
                || text.contains("testImplementation(project(\":icli-comparison\"")
                || text.contains("testRuntimeOnly(project(\":icli-comparison\"");
    }

    @Test
    void comparisonImportsRemainInComparisonModuleOnly() throws Exception {
        Path root = repositoryRoot();
        List<Path> sources = nonComparisonSources(root);
        assertExpectedSourceRootsCovered(root, sources);
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> forbidden : FORBIDDEN_IMPORTS.entrySet()) {
                assertTrue(
                        !containsForbiddenReference(text, forbidden.getKey()),
                        () -> forbidden.getValue() + " reference leaked into " + root.relativize(source));
            }
        }
    }

    private static List<Path> gradleBuildFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root, 2)) {
            return files.filter(path -> path.getFileName().toString().equals("build.gradle.kts"))
                    .filter(path -> !isIgnored(path))
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> nonComparisonSources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(ExternalLibraryBoundaryTest::isSourceFile)
                    .filter(path -> !isIgnored(path))
                    .filter(path -> !path.startsWith(root.resolve("icli-comparison")))
                    .sorted()
                    .toList();
        }
    }

    private static boolean containsForbiddenReference(String text, String packageName) {
        return text.contains("import " + packageName)
                || text.contains("import static " + packageName)
                || text.contains(packageName + ".");
    }

    private static void assertExpectedSourceRootsCovered(Path root, List<Path> sources) {
        Set<String> relativeSources = new HashSet<>();
        for (Path source : sources) {
            relativeSources.add(root.relativize(source).toString());
        }
        assertTrue(
                relativeSources.contains("src/main/java/com/github/ulviar/icli/CommandService.java"),
                "Boundary test must scan core sources");
        assertTrue(
                relativeSources.contains("icli-kotlin/src/main/kotlin/com/github/ulviar/icli/kotlin/IcliKotlin.kt"),
                "Boundary test must scan Kotlin module sources");
        assertTrue(
                relativeSources.contains(
                        "icli-integrations/src/main/java/com/github/ulviar/icli/integration/CommandBackedTool.java"),
                "Boundary test must scan integrations module sources");
    }

    private static boolean isSourceFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    private static boolean isIgnored(Path path) {
        String normalized = path.toString();
        return normalized.contains("/.git/")
                || normalized.contains("/.gradle/")
                || normalized.contains("/build/")
                || normalized.contains("/.idea/");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository root");
    }
}
