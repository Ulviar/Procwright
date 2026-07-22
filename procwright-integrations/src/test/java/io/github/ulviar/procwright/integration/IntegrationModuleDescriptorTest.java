/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

final class IntegrationModuleDescriptorTest {

    @Test
    void integrationArtifactIsNamedJavaModule() throws Exception {
        ModuleDescriptor descriptor = moduleDescriptor();

        assertEquals("io.github.ulviar.procwright.integrations", descriptor.name());
        assertEquals(
                Set.of("io.github.ulviar.procwright.integration"),
                descriptor.exports().stream()
                        .map(ModuleDescriptor.Exports::source)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertTransitiveRequirement(descriptor, "io.github.ulviar.procwright");
        assertTransitiveRequirement(descriptor, "com.fasterxml.jackson.databind");
        assertStaticTransitiveRequirement(descriptor, "org.jspecify");
    }

    private static void assertTransitiveRequirement(ModuleDescriptor descriptor, String moduleName) {
        assertTrue(
                descriptor.requires().stream()
                        .anyMatch(require -> require.name().equals(moduleName)
                                && require.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)),
                () -> "Expected transitive module requirement: " + moduleName);
    }

    private static void assertStaticTransitiveRequirement(ModuleDescriptor descriptor, String moduleName) {
        ModuleDescriptor.Requires requirement = descriptor.requires().stream()
                .filter(candidate -> candidate.name().equals(moduleName))
                .findFirst()
                .orElseThrow();
        assertTrue(requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC));
        assertTrue(requirement.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE));
    }

    private static ModuleDescriptor moduleDescriptor() throws Exception {
        Path classesRoot = Path.of(ProtocolAdapters.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
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
}
