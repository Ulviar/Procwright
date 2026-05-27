package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PublicIntegrationApiSurfaceTest {

    private static final Set<String> PUBLIC_API_TYPES = Set.of(
            "io.github.ulviar.procwright.integration.CancellableCall",
            "io.github.ulviar.procwright.integration.CliAdapterError",
            "io.github.ulviar.procwright.integration.CommandBackedTool",
            "io.github.ulviar.procwright.integration.CommandBackedTool$Handler",
            "io.github.ulviar.procwright.integration.ContentLengthJsonFrames",
            "io.github.ulviar.procwright.integration.IntegrationProtocolException",
            "io.github.ulviar.procwright.integration.IntegrationProtocolException$Reason",
            "io.github.ulviar.procwright.integration.JsonCodec",
            "io.github.ulviar.procwright.integration.JsonLineSession",
            "io.github.ulviar.procwright.integration.JsonLines",
            "io.github.ulviar.procwright.integration.JsonParseException",
            "io.github.ulviar.procwright.integration.JsonValue",
            "io.github.ulviar.procwright.integration.JsonValue$JsonArray",
            "io.github.ulviar.procwright.integration.JsonValue$JsonBoolean",
            "io.github.ulviar.procwright.integration.JsonValue$JsonNull",
            "io.github.ulviar.procwright.integration.JsonValue$JsonNumber",
            "io.github.ulviar.procwright.integration.JsonValue$JsonObject",
            "io.github.ulviar.procwright.integration.JsonValue$JsonString",
            "io.github.ulviar.procwright.integration.ProtocolAdapters",
            "io.github.ulviar.procwright.integration.ToolCallResult",
            "io.github.ulviar.procwright.integration.ToolCallResult$Failure",
            "io.github.ulviar.procwright.integration.ToolCallResult$Success");

    @Test
    void integrationPublicTopLevelTypesStayInIntegrationPackage() throws Exception {
        assertEquals(Set.of("io.github.ulviar.procwright.integration"), publicTopLevelPackages(JsonCodec.class));
    }

    @Test
    void integrationPublicApiTypesStayInApprovedBaseline() throws Exception {
        assertEquals(PUBLIC_API_TYPES, publicApiTypeNames(JsonCodec.class));
    }

    @Test
    void integrationModuleRequiresCoreTransitively() throws Exception {
        ModuleDescriptor descriptor = moduleDescriptor(JsonCodec.class);
        ModuleDescriptor.Requires core = descriptor.requires().stream()
                .filter(requires -> requires.name().equals("io.github.ulviar.procwright"))
                .findFirst()
                .orElseThrow();

        assertTrue(core.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE));
    }

    private static Set<String> publicTopLevelPackages(Class<?> anchor) throws Exception {
        TreeSet<String> packages = new TreeSet<>();
        for (Class<?> type : publicApiTypes(anchor)) {
            packages.add(type.getPackageName());
        }
        return packages;
    }

    private static Set<String> publicApiTypeNames(Class<?> anchor) throws Exception {
        TreeSet<String> typeNames = new TreeSet<>();
        for (Class<?> type : publicApiTypes(anchor)) {
            typeNames.add(type.getName());
        }
        return typeNames;
    }

    private static Set<Class<?>> publicApiTypes(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(classesRoot)) {
            return publicApiTypesFromJar(anchor, classesRoot);
        }
        TreeSet<Class<?>> types = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
        try (Stream<Path> files = Files.walk(classesRoot)) {
            for (Path classFile : files.filter(PublicIntegrationApiSurfaceTest::isTopLevelClass)
                    .toList()) {
                Class<?> type = Class.forName(className(classesRoot, classFile), false, anchor.getClassLoader());
                if (Modifier.isPublic(type.getModifiers())) {
                    types.add(type);
                }
            }
        }
        addPublicNestedTypes(types);
        return types;
    }

    private static ModuleDescriptor moduleDescriptor(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(classesRoot)) {
            try (JarFile jar = new JarFile(classesRoot.toFile())) {
                JarEntry entry = jar.getJarEntry("module-info.class");
                assertTrue(entry != null, "Integration artifact must contain module-info.class");
                try (var input = jar.getInputStream(entry)) {
                    return ModuleDescriptor.read(input);
                }
            }
        }
        Path moduleInfo = classesRoot.resolve("module-info.class");
        assertTrue(Files.isRegularFile(moduleInfo), "Integration classes must contain module-info.class");
        try (var input = Files.newInputStream(moduleInfo)) {
            return ModuleDescriptor.read(input);
        }
    }

    private static Set<Class<?>> publicApiTypesFromJar(Class<?> anchor, Path jarPath) throws Exception {
        TreeSet<Class<?>> types = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry : jar.stream()
                    .filter(PublicIntegrationApiSurfaceTest::isTopLevelClass)
                    .toList()) {
                Class<?> type = Class.forName(className(entry), false, anchor.getClassLoader());
                if (Modifier.isPublic(type.getModifiers())) {
                    types.add(type);
                }
            }
        }
        addPublicNestedTypes(types);
        return types;
    }

    private static void addPublicNestedTypes(Set<Class<?>> types) {
        ArrayDeque<Class<?>> queue = new ArrayDeque<>(types);
        while (!queue.isEmpty()) {
            Class<?> current = queue.removeFirst();
            for (Class<?> nested : current.getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers()) && types.add(nested)) {
                    queue.addLast(nested);
                }
            }
        }
    }

    private static boolean isTopLevelClass(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".class")
                && !name.contains("$")
                && !"module-info.class".equals(name)
                && !"package-info.class".equals(name);
    }

    private static boolean isTopLevelClass(JarEntry entry) {
        String name = entry.getName();
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return !entry.isDirectory()
                && name.endsWith(".class")
                && !fileName.contains("$")
                && !"module-info.class".equals(fileName)
                && !"package-info.class".equals(fileName);
    }

    private static String className(Path classesRoot, Path classFile) {
        String relativeName = classesRoot.relativize(classFile).toString();
        String simpleName = relativeName.substring(0, relativeName.length() - ".class".length());
        return simpleName.replace(File.separatorChar, '.');
    }

    private static String className(JarEntry entry) {
        String entryName = entry.getName();
        return entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
    }
}
