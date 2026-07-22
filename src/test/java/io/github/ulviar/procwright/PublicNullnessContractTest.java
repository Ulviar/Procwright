/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandSpec;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.TypeVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

final class PublicNullnessContractTest {

    private static final Set<String> EXPORTED_PACKAGES = Set.of(
            "io.github.ulviar.procwright",
            "io.github.ulviar.procwright.command",
            "io.github.ulviar.procwright.diagnostics",
            "io.github.ulviar.procwright.preset",
            "io.github.ulviar.procwright.session",
            "io.github.ulviar.procwright.terminal");

    private static final Set<String> EXPORTED_PUBLIC_RECORDS = Set.of(
            "io.github.ulviar.procwright.command.CapturePolicy$Bounded",
            "io.github.ulviar.procwright.command.CapturePolicy$Discard",
            "io.github.ulviar.procwright.command.CapturePolicy$ToPath",
            "io.github.ulviar.procwright.command.CharsetPolicy",
            "io.github.ulviar.procwright.command.CommandResult",
            "io.github.ulviar.procwright.command.ShutdownPolicy",
            "io.github.ulviar.procwright.diagnostics.CommandEcho",
            "io.github.ulviar.procwright.diagnostics.DiagnosticEvent",
            "io.github.ulviar.procwright.session.ExpectMatch",
            "io.github.ulviar.procwright.session.LineResponse",
            "io.github.ulviar.procwright.session.LineTranscript",
            "io.github.ulviar.procwright.session.PooledLineSessionMetrics",
            "io.github.ulviar.procwright.session.PooledProtocolSessionMetrics",
            "io.github.ulviar.procwright.session.ProtocolTranscript",
            "io.github.ulviar.procwright.session.SessionExit",
            "io.github.ulviar.procwright.session.StreamChunk",
            "io.github.ulviar.procwright.session.StreamExit",
            "io.github.ulviar.procwright.session.StreamTranscript",
            "io.github.ulviar.procwright.terminal.PtyRequest",
            "io.github.ulviar.procwright.terminal.TerminalSize");

    private static final Set<String> GENERATED_RECORD_EQUALS = Set.of(
            "io.github.ulviar.procwright.command.CapturePolicy$Bounded",
            "io.github.ulviar.procwright.command.CapturePolicy$Discard",
            "io.github.ulviar.procwright.command.CapturePolicy$ToPath",
            "io.github.ulviar.procwright.command.CharsetPolicy",
            "io.github.ulviar.procwright.command.ShutdownPolicy",
            "io.github.ulviar.procwright.diagnostics.CommandEcho",
            "io.github.ulviar.procwright.diagnostics.DiagnosticEvent",
            "io.github.ulviar.procwright.session.ExpectMatch",
            "io.github.ulviar.procwright.session.LineResponse",
            "io.github.ulviar.procwright.session.LineTranscript",
            "io.github.ulviar.procwright.session.PooledLineSessionMetrics",
            "io.github.ulviar.procwright.session.PooledProtocolSessionMetrics",
            "io.github.ulviar.procwright.session.ProtocolTranscript",
            "io.github.ulviar.procwright.session.SessionExit",
            "io.github.ulviar.procwright.session.StreamChunk",
            "io.github.ulviar.procwright.session.StreamExit",
            "io.github.ulviar.procwright.session.StreamTranscript",
            "io.github.ulviar.procwright.terminal.PtyRequest",
            "io.github.ulviar.procwright.terminal.TerminalSize");

    private static final Set<String> UNION_NULL_POSITIONS = EXPORTED_PUBLIC_RECORDS.stream()
            .map(record -> record + "#equals(java.lang.Object) parameter[0]")
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> NULLABLE_POSITIONS = Set.of(
            "io.github.ulviar.procwright.ProcwrightException#<init>(java.lang.String,java.lang.Throwable) parameter[1]",
            "io.github.ulviar.procwright.command.CommandExecutionException#<init>(io.github.ulviar.procwright.command.CommandExecutionException$Reason,java.lang.String,java.lang.Throwable) parameter[2]",
            "io.github.ulviar.procwright.command.CommandExecutionException#<init>(io.github.ulviar.procwright.command.CommandExecutionException$Reason,java.lang.String,java.lang.Throwable,io.github.ulviar.procwright.command.CommandResult) parameter[2]",
            "io.github.ulviar.procwright.command.CommandExecutionException#<init>(java.lang.String,java.lang.Throwable) parameter[1]",
            "io.github.ulviar.procwright.command.CommandInput#equals(java.lang.Object) parameter[0]",
            "io.github.ulviar.procwright.command.CommandResult#equals(java.lang.Object) parameter[0]",
            "io.github.ulviar.procwright.command.CommandSpec#equals(java.lang.Object) parameter[0]",
            "io.github.ulviar.procwright.session.ExpectException#<init>(io.github.ulviar.procwright.session.ExpectException$Reason,io.github.ulviar.procwright.session.LineTranscript,java.lang.String,java.lang.Throwable) parameter[3]",
            "io.github.ulviar.procwright.session.LineSessionException#<init>(io.github.ulviar.procwright.session.LineSessionException$Reason,io.github.ulviar.procwright.session.LineTranscript,java.lang.String,java.lang.Throwable) parameter[3]",
            "io.github.ulviar.procwright.session.PooledLineSessionException#<init>(io.github.ulviar.procwright.session.PooledLineSessionException$Reason,java.lang.String,java.lang.Throwable) parameter[2]",
            "io.github.ulviar.procwright.session.PooledProtocolSessionException#<init>(io.github.ulviar.procwright.session.PooledProtocolSessionException$Reason,java.lang.String,java.lang.Throwable) parameter[2]",
            "io.github.ulviar.procwright.session.ProtocolSessionException#<init>(io.github.ulviar.procwright.session.ProtocolSessionException$Reason,io.github.ulviar.procwright.session.ProtocolTranscript,java.lang.String,java.lang.Throwable) parameter[3]",
            "io.github.ulviar.procwright.session.ProtocolSessionException#<init>(io.github.ulviar.procwright.session.ProtocolSessionException$Reason,io.github.ulviar.procwright.session.ProtocolTranscript,java.util.OptionalInt,java.lang.String,java.lang.Throwable) parameter[4]",
            "io.github.ulviar.procwright.session.StreamException#<init>(io.github.ulviar.procwright.session.StreamException$Reason,java.lang.String,io.github.ulviar.procwright.session.StreamTranscript,java.lang.Throwable) parameter[3]");

    @Test
    void exportedPackagesAreNullMarkedAndEveryPublicContractHasAnExplicitOutcome() throws Exception {
        Set<String> moduleExports = exportedPackages(moduleDescriptor(CommandSpec.class));
        assertEquals(EXPORTED_PACKAGES, moduleExports, "The nullness allowlist must match the JPMS exports");
        Set<Class<?>> apiTypes = publicApiTypes(CommandSpec.class);
        assertEquals(
                moduleExports,
                apiTypes.stream().map(Class::getPackageName).collect(Collectors.toSet()),
                "Every exported package must contribute a public type to the audit");
        moduleExports.forEach(packageName -> {
            Package exportedPackage = apiTypes.stream()
                    .filter(type -> type.getPackageName().equals(packageName))
                    .findFirst()
                    .orElseThrow()
                    .getPackage();
            assertTrue(
                    exportedPackage.isAnnotationPresent(NullMarked.class), () -> packageName + " must be @NullMarked");
        });

        TreeSet<String> nullable = new TreeSet<>();
        TreeSet<String> unionNull = new TreeSet<>();
        int checkedReferencePositions = 0;
        for (Class<?> type : apiTypes) {
            checkedReferencePositions += scanTypeContract(type, nullable, unionNull);
        }

        assertTrue(checkedReferencePositions > 500, "The audit must traverse the complete exported surface");
        assertEquals(NULLABLE_POSITIONS, nullable);
        assertEquals(UNION_NULL_POSITIONS, unionNull);
    }

    @Test
    void recordEqualsUsesTheJSpecifyUnionNullSpecialCase() throws Exception {
        Set<Class<?>> apiTypes = publicApiTypes(CommandSpec.class);
        TreeSet<String> records = apiTypes.stream()
                .filter(Class::isRecord)
                .map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(EXPORTED_PUBLIC_RECORDS, records);
        assertEquals(19, GENERATED_RECORD_EQUALS.size());

        for (String recordName : records) {
            Class<?> record = Class.forName(recordName, false, CommandSpec.class.getClassLoader());
            Method equals = record.getDeclaredMethod("equals", Object.class);
            boolean explicitlyNullable = equals.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class);
            if (GENERATED_RECORD_EQUALS.contains(recordName)) {
                assertFalse(
                        explicitlyNullable,
                        () -> recordName + " must rely on JSpecify's generated-record UNION_NULL rule");
            } else {
                assertEquals("io.github.ulviar.procwright.command.CommandResult", recordName);
                assertTrue(explicitlyNullable, () -> recordName + " has a handwritten equals and must be @Nullable");
            }
        }

        assertFalse(RecordEqualsNullFixture.generatedEqualsNull());
    }

    @Test
    void manuallyDeclaredNonRecordEqualsParametersAreExplicitlyNullable() throws Exception {
        for (Class<?> type : publicApiTypes(CommandSpec.class)) {
            if (type.isRecord()) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (isEqualsMethod(method)) {
                    assertTrue(
                            method.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class),
                            () -> key(method) + " parameter[0] must be explicitly @Nullable");
                }
            }
        }
    }

    @Test
    void moduleMakesJSpecifyReadableToNamedCompileTimeConsumersButOptionalAtRuntime() throws Exception {
        ModuleDescriptor descriptor = moduleDescriptor(CommandService.class);
        ModuleDescriptor.Requires jspecify = descriptor.requires().stream()
                .filter(requirement -> requirement.name().equals("org.jspecify"))
                .findFirst()
                .orElseThrow();

        assertTrue(jspecify.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC));
        assertTrue(jspecify.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE));
        assertFalse(jspecify.modifiers().contains(ModuleDescriptor.Requires.Modifier.MANDATED));
    }

    @Test
    void annotatedParameterizedOwnerTypeUsesTheExactOwnerPath() throws Exception {
        Field field = AnnotatedOwnerFixture.class.getDeclaredField("value");
        TreeSet<String> nullable = new TreeSet<>();

        int checked = scan(field.getAnnotatedType(), "fixture#value", nullable);

        assertEquals(5, checked);
        assertEquals(Set.of("fixture#value.owner"), nullable);
    }

    @Test
    void annotatedRawMemberOwnerTypeUsesTheExactOwnerPath() throws Exception {
        Field field = AnnotatedOwnerFixture.class.getDeclaredField("rawValue");
        TreeSet<String> nullable = new TreeSet<>();

        scan(field.getAnnotatedType(), "fixture#rawValue", nullable);

        assertEquals(Set.of("fixture#rawValue.owner"), nullable);
    }

    @Test
    void protectedNestedTypesAreAdmittedAndTheirNullnessIsScanned() {
        Set<String> packages = Set.of(ProtectedNestedNullnessFixture.class.getPackageName());
        Class<?> protectedType = nestedFixture("ProtectedApi");
        Class<?> hiddenType = nestedFixture("HiddenApi");
        assertTrue(isExternallySubclassAccessibleApiType(ProtectedNestedNullnessFixture.class, packages));
        assertTrue(isExternallySubclassAccessibleApiType(protectedType, packages));
        assertFalse(isExternallySubclassAccessibleApiType(hiddenType, packages));

        TreeSet<String> nullable = new TreeSet<>();
        scanTypeContract(protectedType, nullable, new TreeSet<>());

        assertEquals(
                Set.of("fixture.ProtectedApi#value"),
                nullable.stream()
                        .map(path -> path.replace(protectedType.getName(), "fixture.ProtectedApi"))
                        .collect(Collectors.toSet()));
    }

    @Test
    void superclassInterfaceReceiverAndThrowsUseExactPaths() {
        TreeSet<String> nullable = new TreeSet<>();
        scanTypeContract(DirectAnnotatedTypeUseFixture.class, nullable, new TreeSet<>());

        String fixture = DirectAnnotatedTypeUseFixture.class.getName();
        assertEquals(
                Set.of(
                        "fixture superclass",
                        "fixture interface[0]",
                        "fixture#traverse() receiver",
                        "fixture#traverse() throws[0]"),
                nullable.stream().map(path -> path.replace(fixture, "fixture")).collect(Collectors.toSet()));
    }

    private static int scanTypeContract(Class<?> type, Set<String> nullable, Set<String> unionNull) {
        int checked = scanTypeParameters(type.getTypeParameters(), type.getName(), nullable);
        AnnotatedType superclass = type.getAnnotatedSuperclass();
        if (superclass != null) {
            checked += scan(superclass, type.getName() + " superclass", nullable);
        }
        checked += scanAll(type.getAnnotatedInterfaces(), type.getName() + " interface", nullable);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (isPublicOrProtected(constructor) && !constructor.isSynthetic()) {
                checked += scanExecutable(constructor, nullable, unionNull);
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (isPublicOrProtected(method) && !method.isSynthetic() && !method.isBridge()) {
                checked += scanExecutable(method, nullable, unionNull);
                if (method.getReturnType() != void.class) {
                    checked += scan(method.getAnnotatedReturnType(), key(method) + " return", nullable);
                }
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (isPublicOrProtected(field) && !field.isSynthetic()) {
                checked += scan(field.getAnnotatedType(), type.getName() + '#' + field.getName(), nullable);
            }
        }
        return checked;
    }

    private static int scanExecutable(Executable executable, Set<String> nullable, Set<String> unionNull) {
        int checked = scanTypeParameters(executable.getTypeParameters(), key(executable), nullable);
        AnnotatedType receiver = executable.getAnnotatedReceiverType();
        if (receiver != null) {
            checked += scan(receiver, key(executable) + " receiver", nullable);
        }
        Parameter[] parameters = executable.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            String position = key(executable) + " parameter[" + index + ']';
            if (index == 0
                    && executable instanceof Method method
                    && isEqualsMethod(method)
                    && EXPORTED_PUBLIC_RECORDS.contains(
                            method.getDeclaringClass().getName())) {
                unionNull.add(position);
            }
            checked += scan(parameters[index].getAnnotatedType(), position, nullable);
        }
        checked += scanAll(executable.getAnnotatedExceptionTypes(), key(executable) + " throws", nullable);
        return checked;
    }

    private static int scanTypeParameters(TypeVariable<?>[] parameters, String owner, Set<String> nullable) {
        int checked = 0;
        for (TypeVariable<?> parameter : parameters) {
            AnnotatedType[] bounds = parameter.getAnnotatedBounds();
            for (int index = 0; index < bounds.length; index++) {
                checked += scan(
                        bounds[index],
                        owner + " typeParameter[" + parameter.getName() + "].bound[" + index + ']',
                        nullable);
            }
        }
        return checked;
    }

    private static int scan(AnnotatedType type, String position, Set<String> nullable) {
        int checked = type.getType() instanceof Class<?> raw && raw.isPrimitive() ? 0 : 1;
        for (Annotation annotation : type.getAnnotations()) {
            String annotationName = annotation.annotationType().getName();
            assertFalse(
                    annotationName.equals("org.jspecify.annotations.NullnessUnspecified"),
                    () -> position + " must not weaken the public contract with @NullnessUnspecified");
        }
        if (type.isAnnotationPresent(Nullable.class)) {
            nullable.add(position);
        }
        AnnotatedType owner = type.getAnnotatedOwnerType();
        if (owner != null) {
            checked += scan(owner, position + ".owner", nullable);
        }
        if (type instanceof AnnotatedArrayType array) {
            checked += scan(array.getAnnotatedGenericComponentType(), position + ".component", nullable);
        } else if (type instanceof AnnotatedParameterizedType parameterized) {
            AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
            for (int index = 0; index < arguments.length; index++) {
                checked += scan(arguments[index], position + ".typeArgument[" + index + ']', nullable);
            }
        } else if (type instanceof AnnotatedWildcardType wildcard) {
            checked += scanAll(wildcard.getAnnotatedLowerBounds(), position + ".lowerBound", nullable);
            checked += scanAll(wildcard.getAnnotatedUpperBounds(), position + ".upperBound", nullable);
        }
        return checked;
    }

    private static int scanAll(AnnotatedType[] types, String position, Set<String> nullable) {
        int checked = 0;
        for (int index = 0; index < types.length; index++) {
            checked += scan(types[index], position + '[' + index + ']', nullable);
        }
        return checked;
    }

    private static boolean isPublicOrProtected(Executable executable) {
        return Modifier.isPublic(executable.getModifiers()) || Modifier.isProtected(executable.getModifiers());
    }

    private static boolean isPublicOrProtected(Field field) {
        return Modifier.isPublic(field.getModifiers()) || Modifier.isProtected(field.getModifiers());
    }

    private static boolean isEqualsMethod(Method method) {
        return isPublicOrProtected(method)
                && !method.isSynthetic()
                && !method.isBridge()
                && method.getName().equals("equals")
                && method.getReturnType() == boolean.class
                && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[] {Object.class});
    }

    private static String key(Executable executable) {
        return executable.getDeclaringClass().getName()
                + '#'
                + (executable instanceof Constructor<?> ? "<init>" : executable.getName())
                + '('
                + java.util.Arrays.stream(executable.getParameterTypes())
                        .map(Class::getName)
                        .collect(java.util.stream.Collectors.joining(","))
                + ')';
    }

    private static Set<Class<?>> publicApiTypes(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> exportedPackages = exportedPackages(moduleDescriptor(anchor));
        TreeSet<String> names = new TreeSet<>();
        if (Files.isRegularFile(classesRoot)) {
            try (JarFile jar = new JarFile(classesRoot.toFile())) {
                jar.stream()
                        .filter(PublicNullnessContractTest::isClass)
                        .map(JarEntry::getName)
                        .forEach(name -> names.add(name.substring(0, name.length() - ".class".length())
                                .replace('/', '.')));
            }
        } else {
            try (var files = Files.walk(classesRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> isClass(path.getFileName().toString()))
                        .map(path -> classesRoot.relativize(path).toString())
                        .forEach(name -> names.add(name.substring(0, name.length() - ".class".length())
                                .replace(File.separatorChar, '.')));
            }
        }

        List<Class<?>> loaded = new ArrayList<>();
        for (String name : names) {
            Class<?> type = Class.forName(name, false, anchor.getClassLoader());
            if (isExternallySubclassAccessibleApiType(type, exportedPackages)) {
                loaded.add(type);
            }
        }
        return Set.copyOf(loaded);
    }

    private static ModuleDescriptor moduleDescriptor(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<java.lang.module.ModuleReference> modules =
                ModuleFinder.of(classesRoot).findAll();
        assertEquals(1, modules.size(), "The public nullness audit requires one explicit module descriptor");
        return modules.iterator().next().descriptor();
    }

    private static Set<String> exportedPackages(ModuleDescriptor descriptor) {
        return descriptor.exports().stream()
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isExternallySubclassAccessibleApiType(Class<?> type, Set<String> packages) {
        if (!packages.contains(type.getPackageName()) || type.isSynthetic()) {
            return false;
        }
        Class<?> current = type;
        while (current.getEnclosingClass() != null) {
            if (!current.isMemberClass()
                    || (!Modifier.isPublic(current.getModifiers()) && !Modifier.isProtected(current.getModifiers()))) {
                return false;
            }
            current = current.getEnclosingClass();
        }
        return Modifier.isPublic(current.getModifiers());
    }

    private static Class<?> nestedFixture(String simpleName) {
        return java.util.Arrays.stream(ProtectedNestedNullnessFixture.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow();
    }

    private static boolean isClass(JarEntry entry) {
        return !entry.isDirectory() && isClass(entry.getName());
    }

    private static boolean isClass(String name) {
        return name.endsWith(".class") && !name.endsWith("module-info.class") && !name.endsWith("package-info.class");
    }

    private static final class AnnotatedOwnerFixture {

        private @Nullable Owner<String>.Nested<Integer> value;

        @SuppressWarnings("rawtypes")
        private @Nullable Owner.RawNested rawValue;
    }

    private static final class Owner<T> {

        private final class Nested<U> {}

        private final class RawNested {}
    }
}
