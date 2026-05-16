package com.github.ulviar.icli.kotlin

import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicKotlinApiSurfaceTest {

    @Test
    fun `kotlin public top level types stay in kotlin package`() {
        assertEquals(
            setOf("com.github.ulviar.icli.kotlin"),
            publicTopLevelPackages(ListenFlowInvocation::class.java),
        )
    }

    private fun publicTopLevelPackages(anchor: Class<*>): Set<String> {
        val classesRoot = Path.of(anchor.protectionDomain.codeSource.location.toURI())
        if (Files.isRegularFile(classesRoot)) {
            return publicTopLevelPackagesFromJar(anchor, classesRoot)
        }
        val files = Files.walk(classesRoot)
        try {
            return files
                .filter(::isTopLevelClass)
                .map { classFile ->
                    Class.forName(className(classesRoot, classFile), false, anchor.classLoader)
                }
                .filter { type -> Modifier.isPublic(type.modifiers) }
                .map { type -> type.packageName }
                .toList()
                .toSortedSet()
        } finally {
            files.close()
        }
    }

    private fun publicTopLevelPackagesFromJar(anchor: Class<*>, jarPath: Path): Set<String> {
        JarFile(jarPath.toFile()).use { jar ->
            return jar.stream()
                .filter(::isTopLevelClass)
                .map { entry -> Class.forName(className(entry), false, anchor.classLoader) }
                .filter { type -> Modifier.isPublic(type.modifiers) }
                .map { type -> type.packageName }
                .toList()
                .toSortedSet()
        }
    }

    private fun isTopLevelClass(path: Path): Boolean {
        val name = path.fileName.toString()
        return name.endsWith(".class") &&
            !name.contains("$") &&
            name != "module-info.class" &&
            name != "package-info.class"
    }

    private fun isTopLevelClass(entry: JarEntry): Boolean {
        val name = entry.name
        val fileName = name.substring(name.lastIndexOf('/') + 1)
        return !entry.isDirectory &&
            name.endsWith(".class") &&
            !fileName.contains("$") &&
            fileName != "module-info.class" &&
            fileName != "package-info.class"
    }

    private fun className(classesRoot: Path, classFile: Path): String {
        val relativeName = classesRoot.relativize(classFile).toString()
        val simpleName = relativeName.substring(0, relativeName.length - ".class".length)
        return simpleName.replace(File.separatorChar, '.')
    }

    private fun className(entry: JarEntry): String {
        val entryName = entry.name
        return entryName.substring(0, entryName.length - ".class".length).replace('/', '.')
    }
}
