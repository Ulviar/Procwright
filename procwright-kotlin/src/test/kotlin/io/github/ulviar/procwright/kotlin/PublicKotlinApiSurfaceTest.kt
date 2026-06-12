/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicKotlinApiSurfaceTest {

    private val publicApiTypes =
        setOf(
            "io.github.ulviar.procwright.kotlin.ProcwrightDsl",
            "io.github.ulviar.procwright.kotlin.ProcwrightKotlinKt",
            "io.github.ulviar.procwright.kotlin.LineWorkerDsl",
            "io.github.ulviar.procwright.kotlin.ListenFlowInvocation",
            "io.github.ulviar.procwright.kotlin.PooledLineSessionDsl",
            "io.github.ulviar.procwright.kotlin.ProtocolAdapterDsl",
        )

    @Test
    fun `kotlin public top level types stay in kotlin package`() {
        assertEquals(
            setOf("io.github.ulviar.procwright.kotlin"),
            publicTopLevelPackages(ListenFlowInvocation::class.java),
        )
    }

    @Test
    fun `kotlin public api types stay in approved baseline`() {
        assertEquals(publicApiTypes, publicApiTypeNames(ListenFlowInvocation::class.java))
    }

    private fun publicTopLevelPackages(anchor: Class<*>): Set<String> =
        publicApiTypes(anchor).map { type -> type.packageName }.toSortedSet()

    private fun publicApiTypeNames(anchor: Class<*>): Set<String> =
        publicApiTypes(anchor).map { type -> type.name }.toSortedSet()

    private fun publicApiTypes(anchor: Class<*>): Set<Class<*>> {
        val classesRoot = Path.of(anchor.protectionDomain.codeSource.location.toURI())
        if (Files.isRegularFile(classesRoot)) {
            return publicApiTypesFromJar(anchor, classesRoot)
        }
        val files = Files.walk(classesRoot)
        try {
            return withPublicNestedTypes(
                files
                    .filter(::isTopLevelClass)
                    .map { classFile ->
                        Class.forName(className(classesRoot, classFile), false, anchor.classLoader)
                    }
                    .filter { type -> Modifier.isPublic(type.modifiers) }
                    .toList()
                    .toSortedSet(compareBy { type -> type.name })
            )
        } finally {
            files.close()
        }
    }

    private fun publicApiTypesFromJar(anchor: Class<*>, jarPath: Path): Set<Class<*>> {
        JarFile(jarPath.toFile()).use { jar ->
            return withPublicNestedTypes(
                jar.stream()
                    .filter(::isTopLevelClass)
                    .map { entry -> Class.forName(className(entry), false, anchor.classLoader) }
                    .filter { type -> Modifier.isPublic(type.modifiers) }
                    .toList()
                    .toSortedSet(compareBy { type -> type.name })
            )
        }
    }

    private fun withPublicNestedTypes(types: Set<Class<*>>): Set<Class<*>> {
        val result = types.toSortedSet(compareBy { type -> type.name })
        val queue = ArrayDeque(result)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.declaredClasses
                .filter { nested -> Modifier.isPublic(nested.modifiers) }
                .forEach { nested ->
                    if (result.add(nested)) {
                        queue.addLast(nested)
                    }
                }
        }
        return result
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
