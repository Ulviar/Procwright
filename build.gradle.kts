import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.diffplug.spotless") version "8.5.1"
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
}

val supportedJavaReleases = setOf(17, 21, 25)
val icliJavaRelease =
    providers
        .gradleProperty("icli.javaRelease")
        .map { value ->
            value.toIntOrNull()
                ?: throw GradleException("icli.javaRelease must be a number: $value")
        }
        .orElse(25)
        .get()

if (icliJavaRelease !in supportedJavaReleases) {
    throw GradleException(
        "icli.javaRelease must be one of $supportedJavaReleases, got $icliJavaRelease"
    )
}

val icliJavaVersion = JavaVersion.toVersion(icliJavaRelease)
val icliVersion = providers.gradleProperty("icli.version").orElse("0.0.0-SNAPSHOT").get()
val releaseVersionPattern =
    Regex(
        """(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-(?:(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"""
    )

extra["icliJavaRelease"] = icliJavaRelease

extra["icliJavaVersion"] = icliJavaVersion

allprojects {
    group = "com.github.ulviar"
    version = icliVersion

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            charSet = "UTF-8"
            docEncoding = "UTF-8"
            addBooleanOption("Werror", true)
        }
    }

    tasks.withType<PublishToMavenRepository>().configureEach {
        doFirst {
            if (icliJavaRelease != 17) {
                throw GradleException(
                    "Public iCLI artifacts must be published with --project-prop=icli.javaRelease=17"
                )
            }
            if (project.version.toString().endsWith("-SNAPSHOT")) {
                throw GradleException(
                    "Public iCLI artifacts must not be published with a SNAPSHOT version; set --project-prop=icli.version=<release-version>"
                )
            }
            if (!releaseVersionPattern.matches(project.version.toString())) {
                throw GradleException(
                    "Public iCLI artifacts must use a SemVer release version such as 0.1.0 or 0.1.0-rc.1"
                )
            }
        }
    }

    tasks.withType<PublishToMavenLocal>().configureEach {
        doFirst {
            if (icliJavaRelease != 17) {
                throw GradleException(
                    "Public iCLI artifacts must be published with --project-prop=icli.javaRelease=17"
                )
            }
        }
    }
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
            artifactId = "icli"
            pom {
                name.set("iCLI")
                description.set(
                    "Scenario-first JVM library for safe external CLI execution and interactive process workflows."
                )
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

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    val integrationTest by creating {
        java.srcDir("src/integrationTest/java")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets.main.get().output
        compileClasspath += configurations.testRuntimeClasspath.get()
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
    }

    val stressTest by creating {
        java.srcDir("src/stressTest/java")
        resources.srcDir("src/stressTest/resources")
        compileClasspath += sourceSets.main.get().output
        compileClasspath += configurations.testRuntimeClasspath.get()
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
    }

    val apiCompatibility by creating { java.srcDir("src/apiCompatibility/java") }
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
    "integrationTestImplementation"(project(":icli-test-cli"))
    "stressTestImplementation"(project(":icli-test-cli"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(icliJavaRelease)
}

tasks.named<Javadoc>("javadoc") {
    modularity.inferModulePath.set(false)
    source =
        sourceSets.main.get().allJava.matching {
            exclude("module-info.java")
            exclude("**/internal/**")
        }
    classpath += sourceSets.main.get().output
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("icli.javaRelease", icliJavaRelease.toString())
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

val publicRuntimeProjects = setOf(project.path, ":icli-kotlin", ":icli-integrations")
val comparisonDependencyCoordinates =
    setOf(
        "org.apache.commons:commons-exec",
        "org.zeroturnaround:zt-exec",
        "com.zaxxer:nuprocess",
        "org.jetbrains.pty4j:pty4j",
        "net.sf.expectit:expectit-core",
        "org.openjdk.jmh:jmh-core",
        "org.openjdk.jmh:jmh-generator-annprocess",
    )

val externalLibraryBoundaryCheck =
    tasks.register("externalLibraryBoundaryCheck") {
        description =
            "Verifies comparison process libraries do not leak into public module classpaths."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            publicRuntimeProjects
                .map { project(it) }
                .forEach { checkedProject ->
                    val runtimeClasspath =
                        checkedProject.configurations.findByName("runtimeClasspath")
                    if (runtimeClasspath != null && runtimeClasspath.isCanBeResolved) {
                        runtimeClasspath.incoming.resolutionResult.allComponents.forEach { component
                            ->
                            val id = component.moduleVersion
                            if (id != null) {
                                val coordinate = "${id.group}:${id.name}"
                                if (coordinate in comparisonDependencyCoordinates) {
                                    throw GradleException(
                                        "Comparison dependency $coordinate leaked into ${checkedProject.path}:runtimeClasspath"
                                    )
                                }
                            }
                        }
                    }

                    checkedProject.configurations.forEach { configuration ->
                        configuration.dependencies.forEach { dependency ->
                            if (
                                dependency is ProjectDependency &&
                                    dependency.path == ":icli-comparison"
                            ) {
                                throw GradleException(
                                    "Public project ${checkedProject.path} must not depend on :icli-comparison"
                                )
                            }
                            val coordinate = "${dependency.group}:${dependency.name}"
                            if (coordinate in comparisonDependencyCoordinates) {
                                throw GradleException(
                                    "Comparison dependency $coordinate leaked into ${checkedProject.path}:${configuration.name}"
                                )
                            }
                        }
                    }
                }
        }
    }

tasks.check { dependsOn(externalLibraryBoundaryCheck) }

tasks.check { dependsOn(":icli-test-cli:check") }

val quickCheck =
    tasks.register("quickCheck") {
        description = "Runs the fast contract/unit verification tier."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named("test"))
    }

val scenarioCheck =
    tasks.register("scenarioCheck") {
        description = "Runs scenario-level verification across core, Kotlin, and integrations."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            quickCheck,
            integrationTest,
            ":icli-kotlin:test",
            ":icli-integrations:test",
            ":icli-consumer-examples:test",
        )
    }

val regressionCheck =
    tasks.register("regressionCheck") {
        description = "Runs bounded stress and public boundary regression checks."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(scenarioCheck, stressTest, externalLibraryBoundaryCheck, ":icli-test-cli:check")
    }

val publicJavaJavadocCheck =
    tasks.register("publicJavaJavadocCheck") {
        description = "Builds public Java Javadocs and fails on every Javadoc warning."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named("javadoc"), ":icli-integrations:javadoc")

        doLast {
            assertStrictJavadocOptions(
                layout.buildDirectory.file("tmp/javadoc/javadoc.options").get().asFile,
                "core",
            )
            assertStrictJavadocOptions(
                project(":icli-integrations")
                    .layout
                    .buildDirectory
                    .file("tmp/javadoc/javadoc.options")
                    .get()
                    .asFile,
                "integrations",
            )
        }
    }

fun assertStrictJavadocOptions(optionsFile: File, moduleName: String) {
    if (!optionsFile.isFile) {
        throw GradleException("Javadoc options file is missing for $moduleName: $optionsFile")
    }
    val options = optionsFile.readText()
    if (!options.contains("-Werror")) {
        throw GradleException("publicJavaJavadocCheck must run $moduleName Javadoc with -Werror")
    }
}

val apiCompatibilityBaselineVersion = "0.1.0"
val apiCompatibilityBaselineDir =
    layout.projectDirectory.dir("config/api-compatibility/$apiCompatibilityBaselineVersion")
val apiCompatibilityMainClass = "com.github.ulviar.icli.build.ApiCompatibilityCheck"

fun apiCompatibilitySpec(
    name: String,
    baselineName: String,
    roots: FileCollection,
    classpath: FileCollection,
    packages: List<String>,
): String =
    listOf(
            name,
            apiCompatibilityBaselineDir.file(baselineName).asFile.absolutePath,
            roots.files.joinToString(File.pathSeparator) { it.absolutePath },
            classpath.files.joinToString(File.pathSeparator) { it.absolutePath },
            packages.joinToString(","),
        )
        .joinToString("|")

fun apiCompatibilitySpecs(): List<String> {
    val coreJar = files(tasks.named<Jar>("jar").map { it.archiveFile })
    val integrationsProject = project(":icli-integrations")
    val integrationsJar = files(integrationsProject.tasks.named<Jar>("jar").map { it.archiveFile })
    val kotlinProject = project(":icli-kotlin")
    val kotlinJar = files(kotlinProject.tasks.named<Jar>("jar").map { it.archiveFile })

    return listOf(
        apiCompatibilitySpec(
            name = "icli",
            baselineName = "icli.txt",
            roots = coreJar,
            classpath = coreJar,
            packages =
                listOf(
                    "com.github.ulviar.icli",
                    "com.github.ulviar.icli.command",
                    "com.github.ulviar.icli.diagnostics",
                    "com.github.ulviar.icli.preset",
                    "com.github.ulviar.icli.session",
                    "com.github.ulviar.icli.terminal",
                ),
        ),
        apiCompatibilitySpec(
            name = "icli-integrations",
            baselineName = "icli-integrations.txt",
            roots = integrationsJar,
            classpath =
                integrationsJar +
                    coreJar +
                    integrationsProject.configurations.getByName("runtimeClasspath"),
            packages = listOf("com.github.ulviar.icli.integration"),
        ),
        apiCompatibilitySpec(
            name = "icli-kotlin",
            baselineName = "icli-kotlin.txt",
            roots = kotlinJar,
            classpath =
                kotlinJar + coreJar + kotlinProject.configurations.getByName("runtimeClasspath"),
            packages = listOf("com.github.ulviar.icli.kotlin"),
        ),
    )
}

val apiCompatibilityCheck =
    tasks.register<JavaExec>("apiCompatibilityCheck") {
        description =
            "Checks current public JVM signatures against the $apiCompatibilityBaselineVersion API baseline."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            tasks.named(sourceSets["apiCompatibility"].classesTaskName),
            tasks.named("jar"),
            ":icli-integrations:jar",
            ":icli-kotlin:jar",
        )
        classpath = sourceSets["apiCompatibility"].runtimeClasspath
        mainClass.set(apiCompatibilityMainClass)
        doFirst { setArgs(listOf("check") + apiCompatibilitySpecs()) }
    }

val writeApiCompatibilityBaseline =
    tasks.register<JavaExec>("writeApiCompatibilityBaseline") {
        description =
            "Writes the $apiCompatibilityBaselineVersion public JVM API baseline from current artifacts."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            tasks.named(sourceSets["apiCompatibility"].classesTaskName),
            tasks.named("jar"),
            ":icli-integrations:jar",
            ":icli-kotlin:jar",
        )
        classpath = sourceSets["apiCompatibility"].runtimeClasspath
        mainClass.set(apiCompatibilityMainClass)
        outputs.dir(apiCompatibilityBaselineDir)
        doFirst { setArgs(listOf("write") + apiCompatibilitySpecs()) }
    }

tasks.check { dependsOn(apiCompatibilityCheck) }

val publicDocsCheck =
    tasks.register<Exec>("publicDocsCheck") {
        description =
            "Builds the public MkDocs documentation site in strict mode and attaches generated API docs."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(publicJavaJavadocCheck)

        val requirements = layout.projectDirectory.file("docs/requirements.txt")
        val config = layout.projectDirectory.file("mkdocs.yml")
        val output = layout.buildDirectory.dir("public-docs")
        val coreJavadocs = layout.buildDirectory.dir("docs/javadoc")
        val integrationJavadocs =
            project(":icli-integrations").layout.buildDirectory.dir("docs/javadoc")

        inputs.file(requirements)
        inputs.file(config)
        inputs.dir(layout.projectDirectory.dir("docs"))
        inputs.dir(coreJavadocs)
        inputs.dir(integrationJavadocs)
        outputs.dir(output)

        commandLine(
            "uv",
            "run",
            "--isolated",
            "--with-requirements",
            requirements.asFile.absolutePath,
            "python",
            "-m",
            "mkdocs",
            "build",
            "--strict",
            "--site-dir",
            output.get().asFile.absolutePath,
        )

        doLast {
            copy {
                from(coreJavadocs)
                into(output.get().asFile.resolve("api/java/core"))
            }
            copy {
                from(integrationJavadocs)
                into(output.get().asFile.resolve("api/java/integrations"))
            }
        }
    }

val cleanWorkingTreeCheck =
    tasks.register("cleanWorkingTreeCheck") {
        description = "Verifies the Git worktree is clean, including untracked files."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        mustRunAfter(
            tasks.named("spotlessCheck"),
            regressionCheck,
            publicJavaJavadocCheck,
            publicDocsCheck,
            ":icli-kotlin:check",
            ":icli-integrations:check",
            ":icli-test-cli:check",
            ":icli-consumer-examples:check",
        )

        doLast {
            val process =
                ProcessBuilder("git", "status", "--porcelain=v1", "--untracked-files=all")
                    .directory(rootDir)
                    .redirectErrorStream(true)
                    .start()
            val status = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("Could not inspect Git working tree:\n$status")
            }
            if (status.isNotEmpty()) {
                throw GradleException(
                    "Working tree must be clean for releaseCandidateCheck:\n$status"
                )
            }
        }
    }

tasks.register("releaseCandidateCheck") {
    description = "Runs the complete local release-candidate verification gate."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(
        tasks.named("spotlessCheck"),
        regressionCheck,
        publicJavaJavadocCheck,
        publicDocsCheck,
        ":icli-kotlin:check",
        ":icli-integrations:check",
        ":icli-test-cli:check",
        ":icli-consumer-examples:check",
        cleanWorkingTreeCheck,
    )
}

spotless {
    java {
        palantirJavaFormat("2.80.0")
        target("src/**/*.java", "icli-*/src/**/*.java")
    }
    kotlin {
        ktfmt("0.58").kotlinlangStyle()
        target("icli-kotlin/src/**/*.kt")
    }
    kotlinGradle {
        ktfmt("0.58").kotlinlangStyle()
        target("*.gradle.kts", "icli-*/*.gradle.kts")
    }
}
