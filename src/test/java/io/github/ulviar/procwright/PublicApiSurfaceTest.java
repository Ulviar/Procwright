/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.Session;
import java.io.File;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PublicApiSurfaceTest {

    private static final Set<String> PUBLIC_API_PACKAGES = Set.of(
            "io.github.ulviar.procwright",
            "io.github.ulviar.procwright.command",
            "io.github.ulviar.procwright.diagnostics",
            "io.github.ulviar.procwright.session",
            "io.github.ulviar.procwright.terminal");

    private static final Set<Class<?>> SCENARIO_NAMESPACES = Set.of(
            RunScenario.class,
            InteractiveScenario.class,
            LineSessionScenario.class,
            StreamScenario.class,
            ProtocolSessionScenario.class);

    private static final Map<Class<?>, Set<String>> DRAFT_TERMINALS = Map.ofEntries(
            Map.entry(RunScenario.Draft.class, Set.of("execute")),
            Map.entry(InteractiveScenario.Draft.class, Set.of("open")),
            Map.entry(LineSessionScenario.Draft.class, Set.of("open", "pooled")),
            Map.entry(LineSessionScenario.PoolDraft.class, Set.of("open")),
            Map.entry(StreamScenario.Draft.class, Set.of("open")),
            Map.entry(ProtocolSessionScenario.Draft.class, Set.of("open", "pooled")),
            Map.entry(ProtocolSessionScenario.PoolDraft.class, Set.of("open")),
            Map.entry(Expect.Draft.class, Set.of("open")));

    @Test
    void corePublicTypesStayInsideTheApprovedPackages() throws Exception {
        Set<String> packages = new TreeSet<>();
        for (Class<?> type : publicApiTypes(CommandService.class)) {
            packages.add(type.getPackageName());
        }

        assertEquals(PUBLIC_API_PACKAGES, packages);
    }

    @Test
    void corePublicSignaturesExposeOnlyJdkAndPublicApiTypes() throws Exception {
        for (Class<?> type : publicApiTypes(CommandService.class)) {
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
                String location = type.getName() + '#' + method.getName();
                assertAllowedTypes(method.getTypeParameters(), location + " type parameters");
                assertAllowedType(method.getGenericReturnType(), location + " return", seenTypes());
                assertAllowedTypes(method.getGenericParameterTypes(), location + " parameters");
                assertAllowedTypes(method.getGenericExceptionTypes(), location + " exceptions");
            }
            for (Field field : type.getFields()) {
                assertAllowedType(field.getGenericType(), type.getName() + '#' + field.getName(), seenTypes());
            }
        }
    }

    @Test
    void scenarioEntryPointsStayScenarioFirstAndStateFree() throws Exception {
        assertEquals(2, declaredPublicMethods(Procwright.class).count());
        assertEntryPoint(Procwright.class, "command", CommandService.class, String.class);
        assertEntryPoint(Procwright.class, "command", CommandService.class, CommandSpec.class);

        assertEquals(5, declaredPublicMethods(CommandService.class).count());
        assertEntryPoint(CommandService.class, "run", RunScenario.Draft.class);
        assertEntryPoint(CommandService.class, "interactive", InteractiveScenario.Draft.class);
        assertEntryPoint(CommandService.class, "lineSession", LineSessionScenario.Draft.class);
        assertEntryPoint(CommandService.class, "listen", StreamScenario.Draft.class);
        assertEntryPoint(CommandService.class, "protocolSession", ProtocolSessionScenario.Draft.class, Supplier.class);

        for (Class<?> type : SCENARIO_NAMESPACES) {
            assertFinalNamespace(type);
            assertEquals(0, declaredPublicMethods(type).count(), type.getName());
        }
        assertFinalNamespace(Procwright.class);
        assertFinalNamespace(CommandService.class);

        assertEquals(
                Set.of(),
                Stream.of(Expect.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .map(Method::getName)
                        .collect(java.util.stream.Collectors.toSet()));

        Method expect = Session.class.getDeclaredMethod("expect");
        assertEquals(Expect.Draft.class, expect.getReturnType());
        assertTrue(expect.isDefault());
    }

    @Test
    void scenarioNamespacesExposeOnlyWriteOnlyDrafts() {
        assertEquals(Set.of("Draft"), publicNestedTypeNames(RunScenario.class));
        assertEquals(Set.of("Draft"), publicNestedTypeNames(InteractiveScenario.class));
        assertEquals(Set.of("Draft", "PoolDraft"), publicNestedTypeNames(LineSessionScenario.class));
        assertEquals(Set.of("Draft"), publicNestedTypeNames(StreamScenario.class));
        assertEquals(Set.of("Draft", "PoolDraft"), publicNestedTypeNames(ProtocolSessionScenario.class));

        assertDraftOwner(RunScenario.Draft.class, RunScenario.class);
        assertDraftOwner(InteractiveScenario.Draft.class, InteractiveScenario.class);
        assertDraftOwner(LineSessionScenario.Draft.class, LineSessionScenario.class);
        assertDraftOwner(LineSessionScenario.PoolDraft.class, LineSessionScenario.class);
        assertDraftOwner(StreamScenario.Draft.class, StreamScenario.class);
        assertDraftOwner(ProtocolSessionScenario.Draft.class, ProtocolSessionScenario.class);
        assertDraftOwner(ProtocolSessionScenario.PoolDraft.class, ProtocolSessionScenario.class);
        assertDraftOwner(Expect.Draft.class, Expect.class);

        for (Map.Entry<Class<?>, Set<String>> entry : DRAFT_TERMINALS.entrySet()) {
            Class<?> draft = entry.getKey();
            assertTrue(draft.isInterface(), draft.getName());
            for (Method method : draft.getMethods()) {
                if (method.getName().startsWith("with") || method.getName().startsWith("on")) {
                    assertEquals(draft, method.getReturnType(), method.toString());
                    assertFalse(hasConfigurationCarrier(method), method.toString());
                } else {
                    assertTrue(
                            entry.getValue().contains(method.getName()),
                            () -> "Draft exposes state or a terminal from another scenario family: " + method);
                    assertEquals(0, method.getParameterCount(), method.toString());
                }
            }
        }
    }

    private static boolean hasConfigurationCarrier(Method method) {
        return Stream.of(method.getParameterTypes())
                .map(Class::getSimpleName)
                .anyMatch(name -> name.endsWith("Options") || name.endsWith("Settings") || name.endsWith("Config"));
    }

    private static void assertEntryPoint(Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        assertTrue(Modifier.isPublic(method.getModifiers()), method.toString());
        assertEquals(returnType, method.getReturnType(), method.toString());
    }

    private static void assertFinalNamespace(Class<?> type) {
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
        assertFalse(type.isInterface(), type.getName());
        assertNoPublicConstructorsOrFields(type);
    }

    private static void assertDraftOwner(Class<?> draft, Class<?> owner) {
        assertEquals(owner, draft.getDeclaringClass(), draft.getName());
        assertTrue(Modifier.isPublic(draft.getModifiers()), draft.getName());
        assertTrue(Modifier.isStatic(draft.getModifiers()), draft.getName());
        assertTrue(draft.isInterface(), draft.getName());
        assertNoPublicConstructorsOrFields(draft);
    }

    private static void assertNoPublicConstructorsOrFields(Class<?> type) {
        assertEquals(0, type.getConstructors().length, type.getName());
        assertEquals(0, type.getFields().length, type.getName());
    }

    private static Stream<Method> declaredPublicMethods(Class<?> type) {
        return Stream.of(type.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers()));
    }

    private static Set<String> publicNestedTypeNames(Class<?> type) {
        return Stream.of(type.getDeclaredClasses())
                .filter(nested -> Modifier.isPublic(nested.getModifiers()))
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<Class<?>> publicApiTypes(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        TreeSet<Class<?>> types = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
        if (Files.isRegularFile(classesRoot)) {
            types.addAll(publicApiTypesFromJar(anchor, classesRoot));
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
            for (Class<?> nested : queue.removeFirst().getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers()) && types.add(nested)) {
                    queue.addLast(nested);
                }
            }
        }
        return types;
    }

    private static Set<Class<?>> publicApiTypesFromJar(Class<?> anchor, Path jarPath) throws Exception {
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
            if (parameterizedType.getOwnerType() != null) {
                assertAllowedType(parameterizedType.getOwnerType(), location, seen);
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
        if (PUBLIC_API_PACKAGES.contains(packageName)) {
            assertTrue(isPubliclyAccessible(type), () -> "Public core API exposes inaccessible " + type.getName());
        }
    }

    private static void assertAllowedPermittedSubclass(Class<?> type, String location) {
        if (!type.getPackageName().startsWith("io.github.ulviar.procwright.internal.")) {
            assertAllowedClass(type, location);
        }
    }

    private static boolean isPublicApiType(Class<?> type) {
        return !type.getName().startsWith("io.github.ulviar.procwright.internal.");
    }

    private static boolean isPubliclyAccessible(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
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
        String fileName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
        return !entry.isDirectory()
                && entry.getName().endsWith(".class")
                && !fileName.contains("$")
                && !"module-info.class".equals(fileName)
                && !"package-info.class".equals(fileName);
    }

    private static String className(Path classesRoot, Path classFile) {
        String relativeName = classesRoot.relativize(classFile).toString();
        return relativeName
                .substring(0, relativeName.length() - ".class".length())
                .replace(File.separatorChar, '.');
    }

    private static String className(JarEntry entry) {
        String name = entry.getName();
        return name.substring(0, name.length() - ".class".length()).replace('/', '.');
    }
}
