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
import kotlin.test.assertTrue

class PublicKotlinApiSurfaceTest {

    private val approvedTypes =
        setOf(
            "io.github.ulviar.procwright.kotlin.CoroutineExtensionsKt",
            "io.github.ulviar.procwright.kotlin.DurationExtensionsKt",
            "io.github.ulviar.procwright.kotlin.ProtocolAdapterFactoryDsl",
            "io.github.ulviar.procwright.kotlin.ProtocolAdapterFactoryKt",
            "io.github.ulviar.procwright.kotlin.StreamFlowKt",
        )

    @Test
    fun `public Kotlin API remains a thin extension layer`() {
        assertEquals(approvedTypes, publicApiTypes().map { it.name }.toSet())
    }

    @Test
    fun `terminal functions do not accept configuration lambdas`() {
        val terminalNames = setOf("executeAwait", "openFlow")
        val terminalMethods = publicMethods().filter { it.name in terminalNames }

        assertEquals(terminalNames, terminalMethods.map { it.name }.toSet())
        assertTrue(terminalMethods.none { method -> method.parameterTypes.any(::isKotlinFunction) })
    }

    @Test
    fun `retired configuration and hidden launch functions are absent`() {
        val forbiddenNames =
            setOf(
                "configuredBy",
                "execute",
                "flow",
                "open",
                "openAwait",
                "pooled",
                "protocolAdapter",
            )

        assertTrue(publicMethods().map { it.name }.toSet().intersect(forbiddenNames).isEmpty())
    }

    @Test
    fun `only adapter factory API accepts lambdas`() {
        val lambdaMethods =
            publicMethods().filter { method -> method.parameterTypes.any(::isKotlinFunction) }

        assertEquals(
            setOf("protocolAdapterFactory", "readResponse", "writeRequest"),
            lambdaMethods.map { it.name }.toSet(),
        )
    }

    private fun publicMethods() =
        publicApiTypes()
            .flatMap { it.declaredMethods.asIterable() }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun publicApiTypes(): Set<Class<*>> {
        val anchor = ProtocolAdapterFactoryDsl::class.java
        val classesRoot = Path.of(anchor.protectionDomain.codeSource.location.toURI())
        if (Files.isRegularFile(classesRoot)) return publicApiTypesFromJar(anchor, classesRoot)
        Files.walk(classesRoot).use { files ->
            return files
                .filter(::isTopLevelClass)
                .map { Class.forName(className(classesRoot, it), false, anchor.classLoader) }
                .filter { Modifier.isPublic(it.modifiers) }
                .toList()
                .toSet()
        }
    }

    private fun publicApiTypesFromJar(anchor: Class<*>, jarPath: Path): Set<Class<*>> =
        JarFile(jarPath.toFile()).use { jar ->
            jar.stream()
                .filter(::isTopLevelClass)
                .map { Class.forName(className(it), false, anchor.classLoader) }
                .filter { Modifier.isPublic(it.modifiers) }
                .toList()
                .toSet()
        }

    private fun isTopLevelClass(path: Path): Boolean {
        val name = path.fileName.toString()
        return name.endsWith(".class") &&
            !name.contains("$") &&
            name != "module-info.class" &&
            name != "package-info.class"
    }

    private fun isTopLevelClass(entry: JarEntry): Boolean {
        val fileName = entry.name.substring(entry.name.lastIndexOf('/') + 1)
        return !entry.isDirectory &&
            entry.name.endsWith(".class") &&
            !fileName.contains("$") &&
            fileName != "module-info.class" &&
            fileName != "package-info.class"
    }

    private fun className(classesRoot: Path, classFile: Path): String {
        val relativeName = classesRoot.relativize(classFile).toString()
        return relativeName
            .substring(0, relativeName.length - ".class".length)
            .replace(File.separatorChar, '.')
    }

    private fun className(entry: JarEntry): String =
        entry.name.substring(0, entry.name.length - ".class".length).replace('/', '.')

    private fun isKotlinFunction(type: Class<*>): Boolean =
        type.name.startsWith("kotlin.jvm.functions.Function")
}
