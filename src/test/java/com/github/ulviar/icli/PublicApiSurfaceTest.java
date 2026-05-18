package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PublicApiSurfaceTest {

    private static final Set<String> PUBLIC_API_PACKAGES = Set.of(
            "com.github.ulviar.icli",
            "com.github.ulviar.icli.command",
            "com.github.ulviar.icli.diagnostics",
            "com.github.ulviar.icli.preset",
            "com.github.ulviar.icli.session",
            "com.github.ulviar.icli.terminal");

    @Test
    void corePublicTopLevelTypesStayInApprovedPackages() throws Exception {
        assertEquals(PUBLIC_API_PACKAGES, publicTopLevelPackages(CommandService.class));
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
                    assertAllowedClass(permittedSubclass, type.getName() + " permitted subclasses");
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

    private static Set<String> publicTopLevelPackages(Class<?> anchor) throws Exception {
        TreeSet<String> packages = new TreeSet<>();
        for (Class<?> type : publicTopLevelTypes(anchor)) {
            packages.add(type.getPackageName());
        }
        return packages;
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

    private static boolean isPublicApiType(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("com.github.ulviar.icli.internal.")) {
            return false;
        }
        return !name.equals("com.github.ulviar.icli.diagnostics.Diagnostics")
                && !name.equals("com.github.ulviar.icli.session.SessionScenarioSupport")
                && !name.equals("com.github.ulviar.icli.session.SessionRuntime")
                && !name.equals("com.github.ulviar.icli.session.StreamRuntime");
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
