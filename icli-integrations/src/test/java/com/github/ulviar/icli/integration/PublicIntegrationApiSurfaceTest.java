package com.github.ulviar.icli.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PublicIntegrationApiSurfaceTest {

    @Test
    void integrationPublicTopLevelTypesStayInIntegrationPackage() throws Exception {
        assertEquals(Set.of("com.github.ulviar.icli.integration"), publicTopLevelPackages(JsonCodec.class));
    }

    private static Set<String> publicTopLevelPackages(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(classesRoot)) {
            return publicTopLevelPackagesFromJar(anchor, classesRoot);
        }
        TreeSet<String> packages = new TreeSet<>();
        try (Stream<Path> files = Files.walk(classesRoot)) {
            for (Path classFile : files.filter(PublicIntegrationApiSurfaceTest::isTopLevelClass)
                    .toList()) {
                Class<?> type = Class.forName(className(classesRoot, classFile), false, anchor.getClassLoader());
                if (Modifier.isPublic(type.getModifiers())) {
                    packages.add(type.getPackageName());
                }
            }
        }
        return packages;
    }

    private static Set<String> publicTopLevelPackagesFromJar(Class<?> anchor, Path jarPath) throws Exception {
        TreeSet<String> packages = new TreeSet<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry : jar.stream()
                    .filter(PublicIntegrationApiSurfaceTest::isTopLevelClass)
                    .toList()) {
                Class<?> type = Class.forName(className(entry), false, anchor.getClassLoader());
                if (Modifier.isPublic(type.getModifiers())) {
                    packages.add(type.getPackageName());
                }
            }
        }
        return packages;
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
