import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

val icliJavaRelease = rootProject.extra["icliJavaRelease"] as Int
val icliJavaVersion = rootProject.extra["icliJavaVersion"] as JavaVersion

dependencies {
    api(project(":"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = icliJavaVersion
    targetCompatibility = icliJavaVersion
    withSourcesJar()
}

kotlin { compilerOptions { jvmTarget.set(kotlinJvmTarget(icliJavaRelease)) } }

val kotlinApiDocsCheck =
    tasks.register("kotlinApiDocsCheck") {
        description = "Checks that public Kotlin API declarations have KDoc."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
        inputs.files(sources)

        doLast {
            sources.files.forEach { source ->
                val lines = source.readLines()
                lines.forEachIndexed { index, line ->
                    if (isPublicApiDeclaration(line) && !hasKdocBefore(lines, index)) {
                        throw GradleException(
                            "Public Kotlin API declaration in ${source.relativeTo(projectDir)}:${index + 1} must have KDoc"
                        )
                    }
                }
            }
        }
    }

tasks.withType<Test>().configureEach { useJUnitPlatform() }

tasks.check { dependsOn(kotlinApiDocsCheck) }

fun isPublicApiDeclaration(line: String): Boolean {
    val trimmed = line.trimStart()
    return !trimmed.startsWith("private ") &&
        (trimmed.startsWith("fun ") ||
            trimmed.startsWith("suspend fun ") ||
            trimmed.startsWith("interface ") ||
            trimmed.startsWith("class "))
}

fun hasKdocBefore(lines: List<String>, declarationIndex: Int): Boolean {
    var index = declarationIndex - 1
    while (index >= 0 && lines[index].isBlank()) {
        index--
    }
    return index >= 0 && lines[index].trimEnd().endsWith("*/")
}

fun kotlinJvmTarget(release: Int): JvmTarget =
    when (release) {
        17 -> JvmTarget.JVM_17
        21 -> JvmTarget.JVM_21
        25 -> JvmTarget.JVM_25
        else -> throw GradleException("Unsupported Kotlin JVM target for Java release $release")
    }
