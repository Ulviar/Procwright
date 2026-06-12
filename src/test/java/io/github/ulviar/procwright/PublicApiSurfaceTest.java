/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PublicApiSurfaceTest {

    private static final Set<String> PUBLIC_API_PACKAGES = Set.of(
            "io.github.ulviar.procwright",
            "io.github.ulviar.procwright.command",
            "io.github.ulviar.procwright.diagnostics",
            "io.github.ulviar.procwright.preset",
            "io.github.ulviar.procwright.session",
            "io.github.ulviar.procwright.terminal");

    private static final Set<String> PUBLIC_API_TYPES = Set.of(
            "io.github.ulviar.procwright.CommandService",
            "io.github.ulviar.procwright.Procwright",
            "io.github.ulviar.procwright.ProcwrightException",
            "io.github.ulviar.procwright.InteractiveScenario",
            "io.github.ulviar.procwright.LineSessionScenario",
            "io.github.ulviar.procwright.PooledLineSessionScenario",
            "io.github.ulviar.procwright.PooledProtocolSessionScenario",
            "io.github.ulviar.procwright.ProtocolSessionScenario",
            "io.github.ulviar.procwright.ReusableProtocolSessionScenario",
            "io.github.ulviar.procwright.RunScenario",
            "io.github.ulviar.procwright.StreamScenario",
            "io.github.ulviar.procwright.command.CapturePolicy",
            "io.github.ulviar.procwright.command.CapturePolicy$Bounded",
            "io.github.ulviar.procwright.command.CapturePolicy$Discard",
            "io.github.ulviar.procwright.command.CapturePolicy$ToPath",
            "io.github.ulviar.procwright.command.CharsetPolicy",
            "io.github.ulviar.procwright.command.CommandException",
            "io.github.ulviar.procwright.command.CommandExecutionException",
            "io.github.ulviar.procwright.command.CommandExecutionException$Reason",
            "io.github.ulviar.procwright.command.CommandInput",
            "io.github.ulviar.procwright.command.CommandInvocation",
            "io.github.ulviar.procwright.command.CommandInvocation$Builder",
            "io.github.ulviar.procwright.command.CommandResult",
            "io.github.ulviar.procwright.command.CommandSpec",
            "io.github.ulviar.procwright.command.CommandSpec$Builder",
            "io.github.ulviar.procwright.command.EnvironmentPolicy",
            "io.github.ulviar.procwright.command.OutputMode",
            "io.github.ulviar.procwright.command.RunOptions",
            "io.github.ulviar.procwright.command.ShutdownPolicy",
            "io.github.ulviar.procwright.diagnostics.CommandEcho",
            "io.github.ulviar.procwright.diagnostics.DiagnosticEvent",
            "io.github.ulviar.procwright.diagnostics.DiagnosticEventType",
            "io.github.ulviar.procwright.diagnostics.DiagnosticListener",
            "io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink",
            "io.github.ulviar.procwright.diagnostics.DiagnosticsOptions",
            "io.github.ulviar.procwright.preset.ScenarioPresets",
            "io.github.ulviar.procwright.session.Expect",
            "io.github.ulviar.procwright.session.ExpectException",
            "io.github.ulviar.procwright.session.ExpectException$Reason",
            "io.github.ulviar.procwright.session.ExpectMatch",
            "io.github.ulviar.procwright.session.ExpectOptions",
            "io.github.ulviar.procwright.session.ExpectOutputFilter",
            "io.github.ulviar.procwright.session.ExpectTranscriptValues",
            "io.github.ulviar.procwright.session.LineResponse",
            "io.github.ulviar.procwright.session.LineSession",
            "io.github.ulviar.procwright.session.LineSessionException",
            "io.github.ulviar.procwright.session.LineSessionException$Reason",
            "io.github.ulviar.procwright.session.LineSessionInvocation",
            "io.github.ulviar.procwright.session.LineSessionInvocation$Builder",
            "io.github.ulviar.procwright.session.LineSessionOptions",
            "io.github.ulviar.procwright.session.LineTranscript",
            "io.github.ulviar.procwright.session.PooledLineSession",
            "io.github.ulviar.procwright.session.PooledLineSessionException",
            "io.github.ulviar.procwright.session.PooledLineSessionException$Reason",
            "io.github.ulviar.procwright.session.PooledLineSessionInvocation",
            "io.github.ulviar.procwright.session.PooledLineSessionInvocation$Builder",
            "io.github.ulviar.procwright.session.PooledLineSessionMetrics",
            "io.github.ulviar.procwright.session.PooledLineSessionOptions",
            "io.github.ulviar.procwright.session.PooledProtocolSession",
            "io.github.ulviar.procwright.session.PooledProtocolSessionException",
            "io.github.ulviar.procwright.session.PooledProtocolSessionException$Reason",
            "io.github.ulviar.procwright.session.PooledProtocolSessionInvocation",
            "io.github.ulviar.procwright.session.PooledProtocolSessionInvocation$Builder",
            "io.github.ulviar.procwright.session.PooledProtocolSessionMetrics",
            "io.github.ulviar.procwright.session.PooledProtocolSessionOptions",
            "io.github.ulviar.procwright.session.PooledWorkerRetireReason",
            "io.github.ulviar.procwright.session.ProtocolAdapter",
            "io.github.ulviar.procwright.session.ProtocolReader",
            "io.github.ulviar.procwright.session.ProtocolReaders",
            "io.github.ulviar.procwright.session.ProtocolSession",
            "io.github.ulviar.procwright.session.ProtocolSessionException",
            "io.github.ulviar.procwright.session.ProtocolSessionException$Reason",
            "io.github.ulviar.procwright.session.ProtocolSessionInvocation",
            "io.github.ulviar.procwright.session.ProtocolSessionInvocation$Builder",
            "io.github.ulviar.procwright.session.ProtocolSessionOptions",
            "io.github.ulviar.procwright.session.ProtocolTranscript",
            "io.github.ulviar.procwright.session.ProtocolWriter",
            "io.github.ulviar.procwright.session.ResponseDecoder",
            "io.github.ulviar.procwright.session.ResponseDecoder$Reader",
            "io.github.ulviar.procwright.session.Session",
            "io.github.ulviar.procwright.session.SessionExit",
            "io.github.ulviar.procwright.session.SessionInvocation",
            "io.github.ulviar.procwright.session.SessionInvocation$Builder",
            "io.github.ulviar.procwright.session.SessionOptions",
            "io.github.ulviar.procwright.session.StreamChunk",
            "io.github.ulviar.procwright.session.StreamException",
            "io.github.ulviar.procwright.session.StreamExit",
            "io.github.ulviar.procwright.session.StreamInvocation",
            "io.github.ulviar.procwright.session.StreamInvocation$Builder",
            "io.github.ulviar.procwright.session.StreamListener",
            "io.github.ulviar.procwright.session.StreamOptions",
            "io.github.ulviar.procwright.session.StreamSession",
            "io.github.ulviar.procwright.session.StreamSource",
            "io.github.ulviar.procwright.session.StreamStdinPolicy",
            "io.github.ulviar.procwright.session.StreamTranscript",
            "io.github.ulviar.procwright.terminal.PtyProvider",
            "io.github.ulviar.procwright.terminal.PtyRequest",
            "io.github.ulviar.procwright.terminal.TerminalPolicy",
            "io.github.ulviar.procwright.terminal.TerminalSignal",
            "io.github.ulviar.procwright.terminal.TerminalSize");

    private static final String MODULE_NAME = "io.github.ulviar.procwright";

    @Test
    void corePublicTopLevelTypesStayInApprovedPackages() throws Exception {
        assertEquals(PUBLIC_API_PACKAGES, publicTopLevelPackages(CommandService.class));
    }

    @Test
    void corePublicApiTypesStayInApprovedBaseline() throws Exception {
        assertEquals(PUBLIC_API_TYPES, publicApiTypeNames(CommandService.class));
    }

    @Test
    void corePublicSignaturesExposeOnlyJdkAndPublicApiTypes() throws Exception {
        for (Class<?> type : publicTopLevelTypes(CommandService.class)) {
            Type genericSuperclass = type.getGenericSuperclass();
            if (genericSuperclass != null) {
                assertAllowedType(genericSuperclass, type.getName() + " superclass", seenTypes());
            }
            assertAllowedTypes(type.getGenericInterfaces(), type.getName() + " interfaces");
            assertAllowedTypes(type.getTypeParameters(), type.getName() + " type parameters");
            Class<?>[] permittedSubclasses = type.getPermittedSubclasses();
            if (permittedSubclasses != null) {
                for (Class<?> permittedSubclass : permittedSubclasses) {
                    assertAllowedPermittedSubclass(permittedSubclass, type.getName() + " permitted subclasses");
                }
            }
            for (Constructor<?> constructor : type.getConstructors()) {
                assertAllowedTypes(constructor.getTypeParameters(), type.getName() + " constructor type parameters");
                assertAllowedTypes(constructor.getGenericParameterTypes(), type.getName() + " constructor parameters");
                assertAllowedTypes(constructor.getGenericExceptionTypes(), type.getName() + " constructor exceptions");
            }
            for (Method method : type.getMethods()) {
                assertAllowedTypes(
                        method.getTypeParameters(), type.getName() + "#" + method.getName() + " type parameters");
                assertAllowedType(
                        method.getGenericReturnType(),
                        type.getName() + "#" + method.getName() + " return",
                        seenTypes());
                assertAllowedTypes(
                        method.getGenericParameterTypes(), type.getName() + "#" + method.getName() + " parameters");
                assertAllowedTypes(
                        method.getGenericExceptionTypes(), type.getName() + "#" + method.getName() + " exceptions");
            }
            for (Field field : type.getFields()) {
                assertAllowedType(field.getGenericType(), type.getName() + "#" + field.getName(), seenTypes());
            }
        }
    }

    @Test
    void moduleDescriptorExportsOnlyPublicApiPackages() throws Exception {
        ModuleDescriptor descriptor = moduleDescriptor(CommandService.class);

        assertEquals(MODULE_NAME, descriptor.name());
        assertEquals(
                PUBLIC_API_PACKAGES,
                descriptor.exports().stream()
                        .map(export -> export.source())
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
    }

    private static Set<String> publicTopLevelPackages(Class<?> anchor) throws Exception {
        TreeSet<String> packages = new TreeSet<>();
        for (Class<?> type : publicTopLevelTypes(anchor)) {
            packages.add(type.getPackageName());
        }
        return packages;
    }

    private static Set<String> publicApiTypeNames(Class<?> anchor) throws Exception {
        TreeSet<String> typeNames = new TreeSet<>();
        for (Class<?> type : publicTopLevelTypes(anchor)) {
            typeNames.add(type.getName());
        }
        return typeNames;
    }

    private static Set<Class<?>> publicTopLevelTypes(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        TreeSet<Class<?>> types = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
        if (Files.isRegularFile(classesRoot)) {
            types.addAll(publicTopLevelTypesFromJar(anchor, classesRoot));
        } else {
            try (Stream<Path> files = Files.walk(classesRoot)) {
                for (Path classFile :
                        files.filter(PublicApiSurfaceTest::isTopLevelClass).toList()) {
                    Class<?> type = Class.forName(className(classesRoot, classFile), false, anchor.getClassLoader());
                    if (Modifier.isPublic(type.getModifiers()) && isPublicApiType(type)) {
                        types.add(type);
                    }
                }
            }
        }
        ArrayDeque<Class<?>> queue = new ArrayDeque<>(types);
        while (!queue.isEmpty()) {
            Class<?> current = queue.removeFirst();
            for (Class<?> nested : current.getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers()) && types.add(nested)) {
                    queue.addLast(nested);
                }
            }
        }
        return types;
    }

    private static Set<Class<?>> publicTopLevelTypesFromJar(Class<?> anchor, Path jarPath) throws Exception {
        TreeSet<Class<?>> types = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry :
                    jar.stream().filter(PublicApiSurfaceTest::isTopLevelClass).toList()) {
                Class<?> type = Class.forName(className(entry), false, anchor.getClassLoader());
                if (Modifier.isPublic(type.getModifiers()) && isPublicApiType(type)) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    private static ModuleDescriptor moduleDescriptor(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(classesRoot)) {
            try (JarFile jar = new JarFile(classesRoot.toFile())) {
                JarEntry entry = jar.getJarEntry("module-info.class");
                assertTrue(entry != null, "Core artifact must contain module-info.class");
                try (var input = jar.getInputStream(entry)) {
                    return ModuleDescriptor.read(input);
                }
            }
        }
        Path moduleInfo = classesRoot.resolve("module-info.class");
        assertTrue(Files.isRegularFile(moduleInfo), "Core classes must contain module-info.class");
        try (var input = Files.newInputStream(moduleInfo)) {
            return ModuleDescriptor.read(input);
        }
    }

    private static void assertAllowedTypes(Type[] types, String location) {
        Set<Type> seen = seenTypes();
        for (Type type : types) {
            assertAllowedType(type, location, seen);
        }
    }

    private static void assertAllowedType(Type type, String location, Set<Type> seen) {
        if (!seen.add(type)) {
            return;
        }
        if (type instanceof Class<?> classType) {
            assertAllowedClass(classType, location);
        } else if (type instanceof ParameterizedType parameterizedType) {
            assertAllowedType(parameterizedType.getRawType(), location, seen);
            Type ownerType = parameterizedType.getOwnerType();
            if (ownerType != null) {
                assertAllowedType(ownerType, location, seen);
            }
            assertAllowedTypes(parameterizedType.getActualTypeArguments(), location, seen);
        } else if (type instanceof GenericArrayType arrayType) {
            assertAllowedType(arrayType.getGenericComponentType(), location, seen);
        } else if (type instanceof TypeVariable<?> variable) {
            assertAllowedTypes(variable.getBounds(), location, seen);
        } else if (type instanceof WildcardType wildcard) {
            assertAllowedTypes(wildcard.getLowerBounds(), location, seen);
            assertAllowedTypes(wildcard.getUpperBounds(), location, seen);
        } else {
            throw new AssertionError("Unsupported reflection type at " + location + ": " + type);
        }
    }

    private static void assertAllowedTypes(Type[] types, String location, Set<Type> seen) {
        for (Type type : types) {
            assertAllowedType(type, location, seen);
        }
    }

    private static Set<Type> seenTypes() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void assertAllowedClass(Class<?> type, String location) {
        if (type.isPrimitive() || type == Void.TYPE) {
            return;
        }
        if (type.isArray()) {
            assertAllowedClass(type.componentType(), location);
            return;
        }
        String packageName = type.getPackageName();
        assertTrue(
                packageName.startsWith("java.") || (PUBLIC_API_PACKAGES.contains(packageName) && isPublicApiType(type)),
                () -> "Public core API leaks " + type.getName() + " at " + location);
    }

    private static void assertAllowedPermittedSubclass(Class<?> type, String location) {
        String packageName = type.getPackageName();
        if (packageName.startsWith("io.github.ulviar.procwright.internal.")) {
            return;
        }
        assertAllowedClass(type, location);
    }

    private static boolean isPublicApiType(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("io.github.ulviar.procwright.internal.")) {
            return false;
        }
        return true;
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
