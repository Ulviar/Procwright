import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless") version "8.10.2"
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

val procwrightJavaRelease = 25
val procwrightVersionProperty = providers.gradleProperty("procwright.version").orNull
val conventionalVersionProperty = providers.gradleProperty("version").orNull

if (
    procwrightVersionProperty != null &&
        conventionalVersionProperty != null &&
        procwrightVersionProperty != conventionalVersionProperty
) {
    throw GradleException(
        "procwright.version and version must match when both Gradle properties are set"
    )
}

val procwrightVersion = procwrightVersionProperty ?: conventionalVersionProperty ?: "0.0.0-SNAPSHOT"

val requireSystemPty =
    providers
        .gradleProperty("procwright.requireSystemPty")
        .map { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else ->
                    throw GradleException(
                        "procwright.requireSystemPty must be true or false, got $value"
                    )
            }
        }
        .orElse(false)
        .get()
val publicMavenGroup = "io.github.ulviar"

allprojects {
    group = publicMavenGroup
    version = procwrightVersion

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(procwrightJavaRelease))
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val kotlinToolingVersion =
            extensions.getByType<KotlinJvmProjectExtension>().coreLibrariesVersion
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(procwrightJavaRelease)
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        }
        dependencies.constraints.add(
            "kotlinAbiValidationCompatClasspath",
            "org.jetbrains.kotlin:kotlin-build-tools-impl",
        ) {
            version { strictly(kotlinToolingVersion) }
            because("ABI validation must use a fixed, verified toolchain")
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set("https://github.com/Ulviar/Procwright")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/Ulviar/Procwright.git")
                        developerConnection.set("scm:git:https://github.com/Ulviar/Procwright.git")
                        url.set("https://github.com/Ulviar/Procwright")
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
    }

    tasks.withType<JavaExec>().configureEach {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(procwrightJavaRelease))
            }
        )
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("procwright.requireSystemPty", requireSystemPty.toString())
        systemProperty("junit.jupiter.execution.timeout.default", "60 s")
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "separate_thread")
        systemProperty("junit.jupiter.execution.timeout.threaddump.enabled", "true")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(procwrightJavaRelease)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            charSet = "UTF-8"
            docEncoding = "UTF-8"
            addBooleanOption("Werror", true)
        }
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "procwright"
            pom {
                name.set("Procwright")
                description.set(
                    "Scenario-first JVM library for safe external CLI execution and interactive process workflows."
                )
            }
        }
    }
}

dependencies {
    compileOnlyApi("org.jspecify:jspecify:1.0.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.snakeyaml:snakeyaml-engine:3.1.1")
    testRuntimeOnly("org.jspecify:jspecify:1.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        compileClasspath += configurations.testRuntimeClasspath.get()
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
    }

    create("stressTest") {
        compileClasspath += sourceSets.main.get().output
        compileClasspath += configurations.testRuntimeClasspath.get()
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

configurations.named("stressTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named("stressTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }

dependencies {
    "integrationTestImplementation"(project(":procwright-test-cli"))
    "stressTestImplementation"(project(":procwright-test-cli"))
}

tasks.named<Javadoc>("javadoc") {
    source = sourceSets.main.get().allJava.matching { exclude("**/internal/**") }
    (options as StandardJavadocDocletOptions).addStringOption(
        "sourcepath",
        sourceSets.main.get().allJava.srcDirs.joinToString(File.pathSeparator) { it.absolutePath },
    )
    inputs.dir(layout.projectDirectory.dir("docs/examples/java"))
    (options as StandardJavadocDocletOptions).addStringOption(
        "-snippet-path",
        layout.projectDirectory.dir("docs/examples/java").asFile.absolutePath,
    )
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests that exercise real processes."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        shouldRunAfter(tasks.test)
    }

val stressTest =
    tasks.register<Test>("stressTest") {
        description = "Runs bounded stress and deadlock-regression tests."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = sourceSets["stressTest"].output.classesDirs
        classpath = sourceSets["stressTest"].runtimeClasspath
        shouldRunAfter(integrationTest)
    }

tasks.check { dependsOn(integrationTest, stressTest) }

tasks.check { dependsOn(":procwright-test-cli:check") }

val publicApiConsumerCompilationCheck =
    tasks.register("publicApiConsumerCompilationCheck") {
        description = "Compiles the standalone public API consumer examples."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            ":procwright-consumer-examples:compileTestJava",
            ":procwright-integrations-consumer-example:compileJava",
            ":procwright-kotlin-consumer-example:compileKotlin",
        )
    }

val quickCheck =
    tasks.register("quickCheck") {
        description = "Runs the fast contract/unit verification tier."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            tasks.named("test"),
            publicApiConsumerCompilationCheck,
            ":procwright-kotlin:checkKotlinAbi",
        )
    }

val scenarioCheck =
    tasks.register("scenarioCheck") {
        description = "Runs scenario-level verification across core, Kotlin, and integrations."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            quickCheck,
            integrationTest,
            ":procwright-kotlin:test",
            ":procwright-integrations:test",
            ":procwright-consumer-examples:test",
            ":procwright-integrations-consumer-example:check",
            ":procwright-kotlin-consumer-example:check",
        )
    }

val regressionCheck =
    tasks.register("regressionCheck") {
        description = "Runs bounded stress and public boundary regression checks."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            scenarioCheck,
            stressTest,
            "externalLibraryBoundaryCheck",
            ":procwright-test-cli:check",
        )
    }

val publicJavaJavadocCheck =
    tasks.register("publicJavaJavadocCheck") {
        description = "Builds public Java Javadocs and fails on every Javadoc warning."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            tasks.named("javadoc"),
            ":procwright-integrations:javadoc",
            ":procwright-consumer-examples:compileJava",
        )
    }

apply(from = "gradle/context-quality.gradle.kts")

apply(from = "gradle/documentation-quality.gradle.kts")

apply(from = "gradle/publication-quality.gradle.kts")

tasks.register("publicationReadinessCheck") {
    description = "Runs product, documentation, and API checks required before publication."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(
        tasks.named("spotlessCheck"),
        regressionCheck,
        tasks.named("publicDocsCheck"),
        tasks.named("externalLibraryBoundaryCheck"),
        tasks.named("publicationStructureCheck"),
        ":procwright-kotlin:kotlinJSpecifyStrictnessCheck",
    )
}

quickCheck.configure { dependsOn(tasks.named("contextLinksCheck")) }

tasks.check {
    dependsOn(tasks.named("contextLinksCheck"), tasks.named("externalLibraryBoundaryCheck"))
}

spotless {
    java {
        palantirJavaFormat("2.98.0")
        target("src/**/*.java", "procwright-*/src/**/*.java", "docs/examples/**/*.java")
        licenseHeader(
            "/* SPDX-License-Identifier: Apache-2.0 */\n\n",
            "(?:/\\*\\*|package |import |open |module )",
        )
    }
    kotlin {
        ktfmt("0.64").kotlinlangStyle()
        target("procwright-kotlin/src/**/*.kt", "docs/examples/**/*.kt")
        licenseHeader(
            "/* SPDX-License-Identifier: Apache-2.0 */\n\n",
            "(?:/\\*\\*|@file|package |import )",
        )
    }
    kotlinGradle {
        ktfmt("0.64").kotlinlangStyle()
        target("*.gradle.kts", "gradle/**/*.gradle.kts", "procwright-*/*.gradle.kts")
    }
}
