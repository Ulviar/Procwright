import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    `maven-publish`
    signing
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
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "icli-kotlin"
            pom {
                name.set("iCLI Kotlin")
                description.set("Optional Kotlin ergonomics for iCLI scenario workflows.")
                url.set("https://github.com/Ulviar/iCLI")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Ulviar/iCLI.git")
                    developerConnection.set("scm:git:https://github.com/Ulviar/iCLI.git")
                    url.set("https://github.com/Ulviar/iCLI")
                }
                developers {
                    developer {
                        id.set("Ulviar")
                        name.set("Ulviar")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Ulviar/iCLI")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (!signingKey.isNullOrBlank() && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
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
