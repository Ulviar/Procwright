package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PackageBoundaryTest {

    private static final String ROOT = "io.github.ulviar.procwright";

    private static final Set<String> DECLARED_CORE_PACKAGES = Set.of(
            ROOT,
            ROOT + ".command",
            ROOT + ".diagnostics",
            ROOT + ".internal",
            ROOT + ".internal.session",
            ROOT + ".preset",
            ROOT + ".session",
            ROOT + ".terminal");

    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.of(
            ROOT,
            packages("command", "diagnostics", "internal", "internal.session", "session", "terminal"),
            ROOT + ".command",
            packages("", "internal"),
            ROOT + ".diagnostics",
            packages("command", "internal", "terminal"),
            ROOT + ".internal",
            packages("command", "diagnostics", "session", "terminal"),
            ROOT + ".internal.session",
            packages("command", "diagnostics", "internal", "session", "terminal"),
            ROOT + ".preset",
            packages("command", "session", "terminal"),
            ROOT + ".session",
            packages("", "command", "diagnostics", "internal", "internal.session", "terminal"),
            ROOT + ".terminal",
            packages("command", "internal"));

    @Test
    void productionClassesStayInDeclaredCorePackages() throws Exception {
        assertEquals(DECLARED_CORE_PACKAGES, productionPackages());
    }

    @Test
    void productionPackageDependenciesFollowDeclaredDirection() throws Exception {
        Map<String, Set<String>> dependencies = productionDependencies();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            String sourcePackage = entry.getKey();
            TreeSet<String> allowed = new TreeSet<>(ALLOWED_DEPENDENCIES.getOrDefault(sourcePackage, Set.of()));
            allowed.add(sourcePackage);
            for (String targetPackage : entry.getValue()) {
                if (!allowed.contains(targetPackage)) {
                    violations.add(sourcePackage + " -> " + targetPackage);
                }
            }
        }

        assertEquals(List.of(), violations);
    }

    private static Set<String> productionPackages() throws Exception {
        TreeSet<String> packages = new TreeSet<>();
        for (ProductionClass productionClass : productionClasses()) {
            packages.add(packageName(productionClass.className()));
        }
        return packages;
    }

    private static Map<String, Set<String>> productionDependencies() throws Exception {
        TreeMap<String, Set<String>> dependencies = new TreeMap<>();
        for (ProductionClass productionClass : productionClasses()) {
            String sourcePackage = packageName(productionClass.className());
            TreeSet<String> targets = new TreeSet<>();
            for (String referencedClass :
                    ClassReferences.read(productionClass.bytecode(), productionClass.className())) {
                String targetPackage = packageName(referencedClass);
                if (isCorePackage(targetPackage) && !sourcePackage.equals(targetPackage)) {
                    targets.add(targetPackage);
                }
            }
            dependencies
                    .computeIfAbsent(sourcePackage, ignored -> new TreeSet<>())
                    .addAll(targets);
        }
        return dependencies;
    }

    private static List<ProductionClass> productionClasses() throws Exception {
        Path classesRoot = classesRoot();
        if (Files.isRegularFile(classesRoot)) {
            return productionClassesFromJar(classesRoot);
        }
        try (Stream<Path> files = Files.walk(classesRoot)) {
            List<Path> classFiles =
                    files.filter(PackageBoundaryTest::isClassFile).sorted().toList();
            List<ProductionClass> classes = new ArrayList<>();
            for (Path classFile : classFiles) {
                classes.add(new ProductionClass(className(classesRoot, classFile), Files.readAllBytes(classFile)));
            }
            return classes;
        }
    }

    private static List<ProductionClass> productionClassesFromJar(Path jarPath) throws IOException {
        List<ProductionClass> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry :
                    jar.stream().filter(PackageBoundaryTest::isClassFile).toList()) {
                try (var input = jar.getInputStream(entry)) {
                    classes.add(new ProductionClass(className(entry), input.readAllBytes()));
                }
            }
        }
        return classes;
    }

    private static Path classesRoot() throws Exception {
        return Path.of(CommandService.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static boolean isClassFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".class") && !"module-info.class".equals(name) && !"package-info.class".equals(name);
    }

    private static boolean isClassFile(JarEntry entry) {
        String name = entry.getName();
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return !entry.isDirectory()
                && name.endsWith(".class")
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

    private static String packageName(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) {
            return "";
        }
        return className.substring(0, lastDot);
    }

    private static boolean isCorePackage(String packageName) {
        return packageName.equals(ROOT) || packageName.startsWith(ROOT + ".");
    }

    private static Set<String> packages(String... names) {
        TreeSet<String> packages = new TreeSet<>();
        for (String name : names) {
            packages.add(name.isEmpty() ? ROOT : ROOT + "." + name);
        }
        return packages;
    }

    private record ProductionClass(String className, byte[] bytecode) {}

    private static final class ClassReferences {

        private static final int CONSTANT_UTF8 = 1;
        private static final int CONSTANT_INTEGER = 3;
        private static final int CONSTANT_FLOAT = 4;
        private static final int CONSTANT_LONG = 5;
        private static final int CONSTANT_DOUBLE = 6;
        private static final int CONSTANT_CLASS = 7;
        private static final int CONSTANT_STRING = 8;
        private static final int CONSTANT_FIELD_REF = 9;
        private static final int CONSTANT_METHOD_REF = 10;
        private static final int CONSTANT_INTERFACE_METHOD_REF = 11;
        private static final int CONSTANT_NAME_AND_TYPE = 12;
        private static final int CONSTANT_METHOD_HANDLE = 15;
        private static final int CONSTANT_METHOD_TYPE = 16;
        private static final int CONSTANT_DYNAMIC = 17;
        private static final int CONSTANT_INVOKE_DYNAMIC = 18;
        private static final int CONSTANT_MODULE = 19;
        private static final int CONSTANT_PACKAGE = 20;
        private static final int CLASSFILE_MAGIC = 0xCAFEBABE;
        private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([^;<>]+)");

        private ClassReferences() {}

        private static Set<String> read(byte[] bytecode, String className) throws IOException {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytecode))) {
                int magic = input.readInt();
                if (magic != CLASSFILE_MAGIC) {
                    throw new IOException("Not a class file: " + className);
                }
                input.readUnsignedShort();
                input.readUnsignedShort();

                int constantPoolCount = input.readUnsignedShort();
                String[] utf8 = new String[constantPoolCount];
                int[] classNameIndexes = new int[constantPoolCount];
                for (int index = 1; index < constantPoolCount; index++) {
                    int tag = input.readUnsignedByte();
                    switch (tag) {
                        case CONSTANT_UTF8 -> utf8[index] = input.readUTF();
                        case CONSTANT_CLASS -> classNameIndexes[index] = input.readUnsignedShort();
                        case CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE ->
                            input.readUnsignedShort();
                        case CONSTANT_FIELD_REF,
                                CONSTANT_METHOD_REF,
                                CONSTANT_INTERFACE_METHOD_REF,
                                CONSTANT_NAME_AND_TYPE,
                                CONSTANT_DYNAMIC,
                                CONSTANT_INVOKE_DYNAMIC -> skipUnsignedShorts(input, 2);
                        case CONSTANT_METHOD_HANDLE -> {
                            input.readUnsignedByte();
                            input.readUnsignedShort();
                        }
                        case CONSTANT_INTEGER, CONSTANT_FLOAT -> input.readInt();
                        case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                            input.readLong();
                            index++;
                        }
                        default -> throw new IOException("Unsupported constant pool tag " + tag + " in " + className);
                    }
                }

                TreeSet<String> references = new TreeSet<>();
                for (int nameIndex : classNameIndexes) {
                    if (nameIndex != 0) {
                        addTypeReference(utf8[nameIndex], references);
                    }
                }
                for (String value : utf8) {
                    addDescriptorReferences(value, references);
                }
                return references;
            }
        }

        private static void addTypeReference(String value, Set<String> references) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (value.startsWith("[")) {
                addDescriptorReferences(value, references);
            } else {
                addInternalName(value, references);
            }
        }

        private static void addDescriptorReferences(String value, Set<String> references) {
            if (value == null) {
                return;
            }
            var matcher = DESCRIPTOR_CLASS.matcher(value);
            while (matcher.find()) {
                addInternalName(matcher.group(1), references);
            }
        }

        private static void addInternalName(String internalName, Set<String> references) {
            if (internalName.indexOf('/') < 0) {
                return;
            }
            references.add(internalName.replace('/', '.'));
        }

        private static void skipUnsignedShorts(DataInputStream input, int count) throws IOException {
            for (int index = 0; index < count; index++) {
                input.readUnsignedShort();
            }
        }
    }
}
