package io.github.ulviar.icli;

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
            "io.github.ulviar.icli",
            "io.github.ulviar.icli.command",
            "io.github.ulviar.icli.diagnostics",
            "io.github.ulviar.icli.preset",
            "io.github.ulviar.icli.session",
            "io.github.ulviar.icli.terminal");

    private static final Set<String> PUBLIC_API_TYPES = Set.of(
            "io.github.ulviar.icli.CommandService",
            "io.github.ulviar.icli.Icli",
            "io.github.ulviar.icli.IcliException",
            "io.github.ulviar.icli.InteractiveScenario",
            "io.github.ulviar.icli.LineSessionScenario",
            "io.github.ulviar.icli.PooledLineSessionScenario",
            "io.github.ulviar.icli.PooledProtocolSessionScenario",
            "io.github.ulviar.icli.ProtocolSessionScenario",
            "io.github.ulviar.icli.ReusableProtocolSessionScenario",
            "io.github.ulviar.icli.RunScenario",
            "io.github.ulviar.icli.StreamScenario",
            "io.github.ulviar.icli.command.CapturePolicy",
            "io.github.ulviar.icli.command.CapturePolicy$Bounded",
            "io.github.ulviar.icli.command.CharsetPolicy",
            "io.github.ulviar.icli.command.CommandException",
            "io.github.ulviar.icli.command.CommandExecutionException",
            "io.github.ulviar.icli.command.CommandExecutionException$Reason",
            "io.github.ulviar.icli.command.CommandInput",
            "io.github.ulviar.icli.command.CommandInvocation",
            "io.github.ulviar.icli.command.CommandInvocation$Builder",
            "io.github.ulviar.icli.command.CommandResult",
            "io.github.ulviar.icli.command.CommandSpec",
            "io.github.ulviar.icli.command.CommandSpec$Builder",
            "io.github.ulviar.icli.command.EnvironmentPolicy",
            "io.github.ulviar.icli.command.OutputMode",
            "io.github.ulviar.icli.command.RunOptions",
            "io.github.ulviar.icli.command.ShutdownPolicy",
            "io.github.ulviar.icli.diagnostics.CommandEcho",
            "io.github.ulviar.icli.diagnostics.DiagnosticEvent",
            "io.github.ulviar.icli.diagnostics.DiagnosticEventType",
            "io.github.ulviar.icli.diagnostics.DiagnosticListener",
            "io.github.ulviar.icli.diagnostics.DiagnosticTranscriptSink",
            "io.github.ulviar.icli.diagnostics.DiagnosticsOptions",
            "io.github.ulviar.icli.preset.ScenarioPresets",
            "io.github.ulviar.icli.session.Expect",
            "io.github.ulviar.icli.session.ExpectException",
            "io.github.ulviar.icli.session.ExpectException$Reason",
            "io.github.ulviar.icli.session.ExpectOptions",
            "io.github.ulviar.icli.session.ExpectOutputFilter",
            "io.github.ulviar.icli.session.ExpectTranscriptValues",
            "io.github.ulviar.icli.session.LineResponse",
            "io.github.ulviar.icli.session.LineSession",
            "io.github.ulviar.icli.session.LineSessionException",
            "io.github.ulviar.icli.session.LineSessionException$Reason",
            "io.github.ulviar.icli.session.LineSessionInvocation",
            "io.github.ulviar.icli.session.LineSessionInvocation$Builder",
            "io.github.ulviar.icli.session.LineSessionOptions",
            "io.github.ulviar.icli.session.LineTranscript",
            "io.github.ulviar.icli.session.PooledLineSession",
            "io.github.ulviar.icli.session.PooledLineSessionException",
            "io.github.ulviar.icli.session.PooledLineSessionException$Reason",
            "io.github.ulviar.icli.session.PooledLineSessionInvocation",
            "io.github.ulviar.icli.session.PooledLineSessionInvocation$Builder",
            "io.github.ulviar.icli.session.PooledLineSessionMetrics",
            "io.github.ulviar.icli.session.PooledLineSessionOptions",
            "io.github.ulviar.icli.session.PooledProtocolSession",
            "io.github.ulviar.icli.session.PooledProtocolSessionException",
            "io.github.ulviar.icli.session.PooledProtocolSessionException$Reason",
            "io.github.ulviar.icli.session.PooledProtocolSessionInvocation",
            "io.github.ulviar.icli.session.PooledProtocolSessionInvocation$Builder",
            "io.github.ulviar.icli.session.PooledProtocolSessionMetrics",
            "io.github.ulviar.icli.session.PooledProtocolSessionOptions",
            "io.github.ulviar.icli.session.PooledWorkerRetireReason",
            "io.github.ulviar.icli.session.ProtocolAdapter",
            "io.github.ulviar.icli.session.ProtocolReader",
            "io.github.ulviar.icli.session.ProtocolReaders",
            "io.github.ulviar.icli.session.ProtocolSession",
            "io.github.ulviar.icli.session.ProtocolSessionException",
            "io.github.ulviar.icli.session.ProtocolSessionException$Reason",
            "io.github.ulviar.icli.session.ProtocolSessionInvocation",
            "io.github.ulviar.icli.session.ProtocolSessionInvocation$Builder",
            "io.github.ulviar.icli.session.ProtocolSessionOptions",
            "io.github.ulviar.icli.session.ProtocolTranscript",
            "io.github.ulviar.icli.session.ProtocolWriter",
            "io.github.ulviar.icli.session.ResponseDecoder",
            "io.github.ulviar.icli.session.ResponseDecoder$Reader",
            "io.github.ulviar.icli.session.Session",
            "io.github.ulviar.icli.session.SessionExit",
            "io.github.ulviar.icli.session.SessionInvocation",
            "io.github.ulviar.icli.session.SessionInvocation$Builder",
            "io.github.ulviar.icli.session.SessionOptions",
            "io.github.ulviar.icli.session.StreamChunk",
            "io.github.ulviar.icli.session.StreamException",
            "io.github.ulviar.icli.session.StreamExit",
            "io.github.ulviar.icli.session.StreamInvocation",
            "io.github.ulviar.icli.session.StreamInvocation$Builder",
            "io.github.ulviar.icli.session.StreamListener",
            "io.github.ulviar.icli.session.StreamOptions",
            "io.github.ulviar.icli.session.StreamSession",
            "io.github.ulviar.icli.session.StreamSource",
            "io.github.ulviar.icli.session.StreamStdinPolicy",
            "io.github.ulviar.icli.session.StreamTranscript",
            "io.github.ulviar.icli.terminal.PtyProvider",
            "io.github.ulviar.icli.terminal.PtyRequest",
            "io.github.ulviar.icli.terminal.TerminalPolicy",
            "io.github.ulviar.icli.terminal.TerminalSignal",
            "io.github.ulviar.icli.terminal.TerminalSize");

    private static final String MODULE_NAME = "io.github.ulviar.icli";

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
        if (packageName.startsWith("io.github.ulviar.icli.internal.")) {
            return;
        }
        assertAllowedClass(type, location);
    }

    private static boolean isPublicApiType(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("io.github.ulviar.icli.internal.")) {
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
