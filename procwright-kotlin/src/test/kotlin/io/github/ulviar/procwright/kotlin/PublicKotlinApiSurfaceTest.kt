/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicKotlinApiSurfaceTest {

    private val publicApiTypes =
        setOf(
            "io.github.ulviar.procwright.kotlin.CoroutineExtensionsKt",
            "io.github.ulviar.procwright.kotlin.DurationExtensionsKt",
            "io.github.ulviar.procwright.kotlin.ProcwrightDsl",
            "io.github.ulviar.procwright.kotlin.ProtocolAdapterFactoryDsl",
            "io.github.ulviar.procwright.kotlin.ProtocolAdapterFactoryKt",
            "io.github.ulviar.procwright.kotlin.StreamFlowKt",
        )

    @Test
    fun `public Kotlin API contains only the Draft extension surface`() {
        assertEquals(publicApiTypes, publicApiTypeNames(ProtocolAdapterFactoryDsl::class.java))
    }

    @Test
    fun `terminal functions do not accept configuration lambdas`() {
        val terminalNames = setOf("executeAwait", "openFlow")
        val terminalMethods =
            publicApiTypes(ProtocolAdapterFactoryDsl::class.java)
                .flatMap { it.declaredMethods.asIterable() }
                .filter {
                    Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.name in terminalNames
                }

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
        val methodNames =
            publicApiTypes(ProtocolAdapterFactoryDsl::class.java)
                .flatMap { it.declaredMethods.asIterable() }
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
                .toSet()

        assertTrue(methodNames.intersect(forbiddenNames).isEmpty())
    }

    @Test
    fun `only adapter factory API accepts lambdas`() {
        val methods =
            publicApiTypes(ProtocolAdapterFactoryDsl::class.java)
                .flatMap { it.declaredMethods.asIterable() }
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        val lambdaMethods =
            methods.filter { method -> method.parameterTypes.any(::isKotlinFunction) }

        assertEquals(
            setOf("protocolAdapterFactory", "readResponse", "writeRequest"),
            lambdaMethods.map { it.name }.toSet(),
        )
    }

    @Test
    fun `public methods have the exact approved JVM descriptors`() {
        assertEquals(expectedMethodDescriptors(), publicMethods().map(::methodDescriptor).toSet())
    }

    @Test
    fun `generic protocol signatures match the complete normalized surface`() {
        val genericMethods =
            publicMethods().filter { method ->
                method.typeParameters.isNotEmpty() ||
                    method.declaringClass == ProtocolAdapterFactoryDsl::class.java
            }

        assertEquals(
            expectedGenericSignatures(),
            genericMethods.map(::normalizedGenericSignature).toSet(),
        )
    }

    private fun publicMethods(): List<Method> =
        publicApiTypes(ProtocolAdapterFactoryDsl::class.java)
            .flatMap { it.declaredMethods.asIterable() }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .sortedWith(compareBy(Method::getName, { methodDescriptor(it) }))

    private fun methodDescriptor(method: Method): String = buildString {
        append(method.declaringClass.name)
        append('#')
        append(method.name)
        append('(')
        append(method.parameterTypes.joinToString(",") { it.name })
        append("):")
        append(method.returnType.name)
    }

    private fun normalizedGenericSignature(method: Method): String = buildString {
        append(method.declaringClass.name)
        append(typeParameterDeclaration(method.declaringClass.typeParameters))
        append('#')
        append(method.name)
        append(typeParameterDeclaration(method.typeParameters))
        append('(')
        append(method.genericParameterTypes.joinToString(",") { normalizeType(it) })
        append("):")
        append(normalizeType(method.genericReturnType))
    }

    private fun typeParameterDeclaration(parameters: Array<out TypeVariable<*>>): String =
        if (parameters.isEmpty()) {
            ""
        } else {
            parameters.joinToString(separator = ",", prefix = "<", postfix = ">") { parameter ->
                "${parameter.name}:${parameter.bounds.joinToString("&") { normalizeType(it) }}"
            }
        }

    private fun normalizeType(type: Type): String =
        when (type) {
            is Class<*> -> type.name
            is TypeVariable<*> -> type.name
            is ParameterizedType ->
                "${normalizeType(type.rawType)}<${type.actualTypeArguments.joinToString(",") { normalizeType(it) }}>"
            is WildcardType -> {
                when {
                    type.lowerBounds.isNotEmpty() ->
                        "? super ${type.lowerBounds.joinToString("&") { normalizeType(it) }}"
                    type.upperBounds.contentEquals(arrayOf<Type>(Any::class.java)) -> "?"
                    else -> "? extends ${type.upperBounds.joinToString("&") { normalizeType(it) }}"
                }
            }
            is GenericArrayType -> "${normalizeType(type.genericComponentType)}[]"
            else -> error("Unsupported generic type: $type")
        }

    private fun expectedGenericSignatures(): Set<String> = buildSet {
        val coroutine = "io.github.ulviar.procwright.kotlin.CoroutineExtensionsKt"
        val duration = "io.github.ulviar.procwright.kotlin.DurationExtensionsKt"
        val factory = "io.github.ulviar.procwright.kotlin.ProtocolAdapterFactoryKt"
        val dsl = "io.github.ulviar.procwright.kotlin.ProtocolAdapterFactoryDsl"
        val typeParameters = "<I:java.lang.Object,O:java.lang.Object>"
        val protocolDraft = "io.github.ulviar.procwright.ProtocolSessionScenario\$Draft<I,O>"
        val protocolPoolDraft =
            "io.github.ulviar.procwright.ProtocolSessionScenario\$PoolDraft<I,O>"
        val protocolSession = "io.github.ulviar.procwright.session.ProtocolSession<I,O>"
        val pooledProtocol = "io.github.ulviar.procwright.session.PooledProtocolSession<I,O>"
        val continuation = "kotlin.coroutines.Continuation"
        val objectType = "java.lang.Object"

        add(
            "$coroutine#awaitExit$typeParameters($protocolSession,$continuation<? super io.github.ulviar.procwright.session.SessionExit>):$objectType"
        )
        add(
            "$coroutine#requestAwait$typeParameters($protocolSession,I,$continuation<? super O>):$objectType"
        )
        add(
            "$coroutine#requestAwait-exY8QGI$typeParameters($protocolSession,I,long,$continuation<? super O>):$objectType"
        )
        add(
            "$coroutine#requestAwait$typeParameters($pooledProtocol,I,$continuation<? super O>):$objectType"
        )
        add(
            "$coroutine#requestAwait-exY8QGI$typeParameters($pooledProtocol,I,long,$continuation<? super O>):$objectType"
        )

        listOf(
                "withIdleTimeout-HG0u8IE",
                "withReadinessTimeout-HG0u8IE",
                "withRequestTimeout-HG0u8IE",
            )
            .forEach { method ->
                add("$duration#$method$typeParameters($protocolDraft,long):$protocolDraft")
            }
        listOf(
                "withAcquireTimeout-HG0u8IE",
                "withHookTimeout-HG0u8IE",
                "withCloseTimeout-HG0u8IE",
                "withMaxWorkerAge-HG0u8IE",
            )
            .forEach { method ->
                add("$duration#$method$typeParameters($protocolPoolDraft,long):$protocolPoolDraft")
            }
        add("$duration#request-SxA4cEA$typeParameters($protocolSession,I,long):O")
        add("$duration#request-SxA4cEA$typeParameters($pooledProtocol,I,long):O")

        val factoryDsl = "$dsl<I,O>"
        add(
            "$factory#protocolAdapterFactory$typeParameters(kotlin.jvm.functions.Function1<? super $factoryDsl,kotlin.Unit>):java.util.function.Supplier<io.github.ulviar.procwright.session.ProtocolAdapter<I,O>>"
        )
        val dslOwner = "$dsl$typeParameters"
        add(
            "$dslOwner#writeRequest(kotlin.jvm.functions.Function2<? super I,? super io.github.ulviar.procwright.session.ProtocolWriter,kotlin.Unit>):void"
        )
        add(
            "$dslOwner#readResponse(kotlin.jvm.functions.Function1<? super io.github.ulviar.procwright.session.ProtocolReaders,? extends O>):void"
        )
    }

    private fun expectedMethodDescriptors(): Set<String> = buildSet {
        val coroutine = "io.github.ulviar.procwright.kotlin.CoroutineExtensionsKt"
        val duration = "io.github.ulviar.procwright.kotlin.DurationExtensionsKt"
        val adapter = "io.github.ulviar.procwright.kotlin.ProtocolAdapterFactoryKt"
        val adapterDsl = "io.github.ulviar.procwright.kotlin.ProtocolAdapterFactoryDsl"
        val stream = "io.github.ulviar.procwright.kotlin.StreamFlowKt"
        val continuation = "kotlin.coroutines.Continuation"
        val objectType = "java.lang.Object"
        val string = "java.lang.String"
        val runDraft = "io.github.ulviar.procwright.RunScenario\$Draft"
        val interactiveDraft = "io.github.ulviar.procwright.InteractiveScenario\$Draft"
        val lineDraft = "io.github.ulviar.procwright.LineSessionScenario\$Draft"
        val linePoolDraft = "io.github.ulviar.procwright.LineSessionScenario\$PoolDraft"
        val streamDraft = "io.github.ulviar.procwright.StreamScenario\$Draft"
        val protocolDraft = "io.github.ulviar.procwright.ProtocolSessionScenario\$Draft"
        val protocolPoolDraft = "io.github.ulviar.procwright.ProtocolSessionScenario\$PoolDraft"
        val session = "io.github.ulviar.procwright.session.Session"
        val lineSession = "io.github.ulviar.procwright.session.LineSession"
        val protocolSession = "io.github.ulviar.procwright.session.ProtocolSession"
        val pooledLine = "io.github.ulviar.procwright.session.PooledLineSession"
        val pooledProtocol = "io.github.ulviar.procwright.session.PooledProtocolSession"
        val streamSession = "io.github.ulviar.procwright.session.StreamSession"
        val expect = "io.github.ulviar.procwright.session.Expect"
        val expectDraft = "io.github.ulviar.procwright.session.Expect\$Draft"
        val pattern = "java.util.regex.Pattern"

        addDescriptor(coroutine, "executeAwait", objectType, runDraft, continuation)
        listOf(session, lineSession, protocolSession, streamSession).forEach {
            addDescriptor(coroutine, "awaitExit", objectType, it, continuation)
        }
        addDescriptor(coroutine, "requestAwait", objectType, lineSession, string, continuation)
        addDescriptor(
            coroutine,
            "requestAwait-exY8QGI",
            objectType,
            lineSession,
            string,
            "long",
            continuation,
        )
        addDescriptor(
            coroutine,
            "requestAwait",
            objectType,
            protocolSession,
            objectType,
            continuation,
        )
        addDescriptor(
            coroutine,
            "requestAwait-exY8QGI",
            objectType,
            protocolSession,
            objectType,
            "long",
            continuation,
        )
        addDescriptor(coroutine, "requestAwait", objectType, pooledLine, string, continuation)
        addDescriptor(
            coroutine,
            "requestAwait-exY8QGI",
            objectType,
            pooledLine,
            string,
            "long",
            continuation,
        )
        addDescriptor(
            coroutine,
            "requestAwait",
            objectType,
            pooledProtocol,
            objectType,
            continuation,
        )
        addDescriptor(
            coroutine,
            "requestAwait-exY8QGI",
            objectType,
            pooledProtocol,
            objectType,
            "long",
            continuation,
        )

        addDescriptor(duration, "withTimeout-HG0u8IE", runDraft, runDraft, "long")
        addDescriptor(
            duration,
            "withIdleTimeout-HG0u8IE",
            interactiveDraft,
            interactiveDraft,
            "long",
        )
        addDescriptor(
            duration,
            "withReadinessTimeout-HG0u8IE",
            interactiveDraft,
            interactiveDraft,
            "long",
        )
        addDescriptor(duration, "withIdleTimeout-HG0u8IE", lineDraft, lineDraft, "long")
        addDescriptor(duration, "withReadinessTimeout-HG0u8IE", lineDraft, lineDraft, "long")
        addDescriptor(duration, "withRequestTimeout-HG0u8IE", lineDraft, lineDraft, "long")
        addDescriptor(duration, "withAcquireTimeout-HG0u8IE", linePoolDraft, linePoolDraft, "long")
        addDescriptor(duration, "withHookTimeout-HG0u8IE", linePoolDraft, linePoolDraft, "long")
        addDescriptor(duration, "withCloseTimeout-HG0u8IE", linePoolDraft, linePoolDraft, "long")
        addDescriptor(duration, "withMaxWorkerAge-HG0u8IE", linePoolDraft, linePoolDraft, "long")
        addDescriptor(duration, "withTimeout-HG0u8IE", streamDraft, streamDraft, "long")
        addDescriptor(duration, "withIdleTimeout-HG0u8IE", protocolDraft, protocolDraft, "long")
        addDescriptor(
            duration,
            "withReadinessTimeout-HG0u8IE",
            protocolDraft,
            protocolDraft,
            "long",
        )
        addDescriptor(duration, "withRequestTimeout-HG0u8IE", protocolDraft, protocolDraft, "long")
        addDescriptor(
            duration,
            "withAcquireTimeout-HG0u8IE",
            protocolPoolDraft,
            protocolPoolDraft,
            "long",
        )
        addDescriptor(
            duration,
            "withHookTimeout-HG0u8IE",
            protocolPoolDraft,
            protocolPoolDraft,
            "long",
        )
        addDescriptor(
            duration,
            "withCloseTimeout-HG0u8IE",
            protocolPoolDraft,
            protocolPoolDraft,
            "long",
        )
        addDescriptor(
            duration,
            "withMaxWorkerAge-HG0u8IE",
            protocolPoolDraft,
            protocolPoolDraft,
            "long",
        )
        addDescriptor(duration, "withTimeout-HG0u8IE", expectDraft, expectDraft, "long")
        addDescriptor(
            duration,
            "request-SxA4cEA",
            "io.github.ulviar.procwright.session.LineResponse",
            lineSession,
            string,
            "long",
        )
        addDescriptor(duration, "request-SxA4cEA", objectType, protocolSession, objectType, "long")
        addDescriptor(
            duration,
            "request-SxA4cEA",
            "io.github.ulviar.procwright.session.LineResponse",
            pooledLine,
            string,
            "long",
        )
        addDescriptor(duration, "request-SxA4cEA", objectType, pooledProtocol, objectType, "long")
        addDescriptor(duration, "expectText-SxA4cEA", expect, expect, string, "long")
        addDescriptor(duration, "expectRegex-SxA4cEA", expect, expect, pattern, "long")
        addDescriptor(
            duration,
            "expectTextMatch-SxA4cEA",
            "io.github.ulviar.procwright.session.ExpectMatch",
            expect,
            string,
            "long",
        )
        addDescriptor(
            duration,
            "expectRegexMatch-SxA4cEA",
            "io.github.ulviar.procwright.session.ExpectMatch",
            expect,
            pattern,
            "long",
        )

        addDescriptor(
            adapter,
            "protocolAdapterFactory",
            "java.util.function.Supplier",
            "kotlin.jvm.functions.Function1",
        )
        addDescriptor(adapterDsl, "writeRequest", "void", "kotlin.jvm.functions.Function2")
        addDescriptor(adapterDsl, "readResponse", "void", "kotlin.jvm.functions.Function1")
        addDescriptor(stream, "openFlow", "kotlinx.coroutines.flow.Flow", streamDraft)
    }

    private fun MutableSet<String>.addDescriptor(
        owner: String,
        method: String,
        returnType: String,
        vararg parameters: String,
    ) {
        add("$owner#$method(${parameters.joinToString(",")}):$returnType")
    }

    private fun publicApiTypeNames(anchor: Class<*>): Set<String> =
        publicApiTypes(anchor).map { it.name }.toSortedSet()

    private fun publicApiTypes(anchor: Class<*>): Set<Class<*>> {
        val classesRoot = Path.of(anchor.protectionDomain.codeSource.location.toURI())
        if (Files.isRegularFile(classesRoot)) return publicApiTypesFromJar(anchor, classesRoot)
        Files.walk(classesRoot).use { files ->
            return files
                .filter(::isTopLevelClass)
                .map { Class.forName(className(classesRoot, it), false, anchor.classLoader) }
                .filter { Modifier.isPublic(it.modifiers) }
                .toList()
                .toSortedSet(compareBy { it.name })
        }
    }

    private fun publicApiTypesFromJar(anchor: Class<*>, jarPath: Path): Set<Class<*>> =
        JarFile(jarPath.toFile()).use { jar ->
            jar.stream()
                .filter(::isTopLevelClass)
                .map { Class.forName(className(it), false, anchor.classLoader) }
                .filter { Modifier.isPublic(it.modifiers) }
                .toList()
                .toSortedSet(compareBy { it.name })
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
