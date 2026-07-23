import java.nio.file.Path
import java.util.ArrayDeque
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.w3c.dom.Element

plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless") version "8.6.0"
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
}

val supportedJavaReleases = setOf(17, 21, 25)
val procwrightJavaRelease =
    providers
        .gradleProperty("procwright.javaRelease")
        .map { value ->
            value.toIntOrNull()
                ?: throw GradleException("procwright.javaRelease must be a number: $value")
        }
        .orElse(25)
        .get()

if (procwrightJavaRelease !in supportedJavaReleases) {
    throw GradleException(
        "procwright.javaRelease must be one of $supportedJavaReleases, got $procwrightJavaRelease"
    )
}

val procwrightJavaVersion = JavaVersion.toVersion(procwrightJavaRelease)
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

extra["procwrightJavaRelease"] = procwrightJavaRelease

extra["procwrightJavaVersion"] = procwrightJavaVersion

allprojects {
    group = publicMavenGroup
    version = procwrightVersion

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("procwright.javaRelease", procwrightJavaRelease.toString())
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

    tasks.withType<PublishToMavenLocal>().configureEach {
        doFirst {
            if (procwrightJavaRelease != 17) {
                throw GradleException(
                    "Public Procwright artifacts must be published with --project-prop=procwright.javaRelease=17"
                )
            }
        }
    }
}

java {
    sourceCompatibility = procwrightJavaVersion
    targetCompatibility = procwrightJavaVersion
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

dependencies {
    compileOnlyApi("org.jspecify:jspecify:1.0.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.snakeyaml:snakeyaml-engine:3.0.1")
    testRuntimeOnly("org.jspecify:jspecify:1.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    val integrationTest by creating {
        compileClasspath += sourceSets.main.get().output
        compileClasspath += configurations.testRuntimeClasspath.get()
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
    }

    val stressTest by creating {
        compileClasspath += sourceSets.main.get().output
        compileClasspath += configurations.testRuntimeClasspath.get()
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
    }

    val apiCompatibility by creating

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
    "apiCompatibilityImplementation"("org.jspecify:jspecify:1.0.0")
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

val publicRuntimeProjects = setOf(project.path, ":procwright-kotlin", ":procwright-integrations")
val forbiddenProcessLibraryCoordinates =
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
            "Verifies public-module dependency boundaries, including compile-only JSpecify and optional Jackson."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            val jspecifyDependencies =
                configurations.getByName("compileOnlyApi").dependencies.filter {
                    it.group == "org.jspecify" && it.name == "jspecify"
                }
            if (
                jspecifyDependencies.size != 1 || jspecifyDependencies.single().version != "1.0.0"
            ) {
                throw GradleException(
                    "Core compileOnlyApi must contain exactly org.jspecify:jspecify:1.0.0"
                )
            }

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
                                if (coordinate == "org.jspecify:jspecify") {
                                    throw GradleException(
                                        "Compile-only JSpecify metadata leaked into ${checkedProject.path}:runtimeClasspath"
                                    )
                                }
                                if (
                                    checkedProject.path != ":procwright-integrations" &&
                                        id.group.startsWith("com.fasterxml.jackson")
                                ) {
                                    throw GradleException(
                                        "Jackson dependency $coordinate leaked into ${checkedProject.path}:runtimeClasspath"
                                    )
                                }
                                if (coordinate in forbiddenProcessLibraryCoordinates) {
                                    throw GradleException(
                                        "External process runtime $coordinate leaked into ${checkedProject.path}:runtimeClasspath"
                                    )
                                }
                            }
                        }
                    }

                    checkedProject.configurations.forEach { configuration ->
                        configuration.dependencies.forEach { dependency ->
                            val coordinate = "${dependency.group}:${dependency.name}"
                            if (coordinate in forbiddenProcessLibraryCoordinates) {
                                throw GradleException(
                                    "External process runtime $coordinate leaked into ${checkedProject.path}:${configuration.name}"
                                )
                            }
                        }
                    }
                }
        }
    }

tasks.check { dependsOn(externalLibraryBoundaryCheck) }

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
            "apiCompatibilityCheck",
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
            externalLibraryBoundaryCheck,
            ":procwright-test-cli:check",
        )
    }

val publicJavaJavadocCheck =
    tasks.register("publicJavaJavadocCheck") {
        description = "Builds public Java Javadocs and fails on every Javadoc warning."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named("javadoc"), ":procwright-integrations:javadoc")
    }

val apiCompatibilityBaselineVersion = "0.1.0"
val apiCompatibilityBaselineDir =
    layout.projectDirectory.dir("config/api-compatibility/$apiCompatibilityBaselineVersion")
val apiCompatibilityMainClass = "io.github.ulviar.procwright.build.ApiCompatibilityCheck"

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
    val integrationsProject = project(":procwright-integrations")
    val integrationsJar = files(integrationsProject.tasks.named<Jar>("jar").map { it.archiveFile })
    val kotlinProject = project(":procwright-kotlin")
    val kotlinJar = files(kotlinProject.tasks.named<Jar>("jar").map { it.archiveFile })

    return listOf(
        apiCompatibilitySpec(
            name = "procwright",
            baselineName = "procwright.txt",
            roots = coreJar,
            classpath = coreJar + configurations.compileClasspath.get(),
            packages =
                listOf(
                    "io.github.ulviar.procwright",
                    "io.github.ulviar.procwright.command",
                    "io.github.ulviar.procwright.diagnostics",
                    "io.github.ulviar.procwright.session",
                    "io.github.ulviar.procwright.terminal",
                ),
        ),
        apiCompatibilitySpec(
            name = "procwright-integrations",
            baselineName = "procwright-integrations.txt",
            roots = integrationsJar,
            classpath =
                integrationsJar +
                    coreJar +
                    integrationsProject.configurations.getByName("runtimeClasspath"),
            packages = listOf("io.github.ulviar.procwright.integration"),
        ),
        apiCompatibilitySpec(
            name = "procwright-kotlin",
            baselineName = "procwright-kotlin.txt",
            roots = kotlinJar,
            classpath =
                kotlinJar + coreJar + kotlinProject.configurations.getByName("runtimeClasspath"),
            packages = listOf("io.github.ulviar.procwright.kotlin"),
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
            ":procwright-integrations:jar",
            ":procwright-kotlin:jar",
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
            ":procwright-integrations:jar",
            ":procwright-kotlin:jar",
        )
        classpath = sourceSets["apiCompatibility"].runtimeClasspath
        mainClass.set(apiCompatibilityMainClass)
        outputs.dir(apiCompatibilityBaselineDir)
        outputs.upToDateWhen { false }
        doFirst {
            if (procwrightJavaRelease != 17) {
                throw GradleException(
                    "API compatibility baselines must be written from Java 17 artifacts; pass --project-prop=procwright.javaRelease=17"
                )
            }
            setArgs(listOf("write") + apiCompatibilitySpecs())
        }
    }

tasks.check { dependsOn(apiCompatibilityCheck) }

val docsRequirementsLockCheck =
    tasks.register("docsRequirementsLockCheck") {
        description = "Checks that the hashed documentation lock matches top-level requirements."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        val requirements = layout.projectDirectory.file("docs/requirements.txt")
        val lock = layout.projectDirectory.file("docs/requirements.lock")
        inputs.files(requirements, lock)

        doLast {
            val requirementPattern = Regex("^([A-Za-z0-9_.-]+)==([^\\s\\\\]+)(?:\\s+\\\\)?$")
            fun normalizedName(name: String): String =
                name.lowercase().replace('_', '-').replace('.', '-')

            fun pins(file: File): Map<String, String> =
                file
                    .readLines()
                    .mapNotNull { line -> requirementPattern.matchEntire(line.trim()) }
                    .associate { match ->
                        normalizedName(match.groupValues[1]) to match.groupValues[2]
                    }

            val requiredPins = pins(requirements.asFile)
            val lockedPins = pins(lock.asFile)
            if (requiredPins.isEmpty()) {
                throw GradleException("docs/requirements.txt contains no exact package pins")
            }
            requiredPins.forEach { (name, version) ->
                val lockedVersion = lockedPins[name]
                if (lockedVersion != version) {
                    throw GradleException(
                        "docs/requirements.lock must pin $name==$version, found ${lockedVersion ?: "no entry"}"
                    )
                }
            }

            val lockLines = lock.asFile.readLines()
            val packageLineIndexes =
                lockLines.indices.filter { index ->
                    requirementPattern.matches(lockLines[index].trim())
                }
            packageLineIndexes.forEachIndexed { position, start ->
                val end = packageLineIndexes.getOrElse(position + 1) { lockLines.size }
                if (
                    lockLines.subList(start + 1, end).none { line ->
                        line.trimStart().startsWith("--hash=sha256:")
                    }
                ) {
                    throw GradleException(
                        "Documentation lock entry has no SHA-256 hash: ${lockLines[start].trim()}"
                    )
                }
            }
        }
    }

fun contextHygieneFailures(
    repositoryPaths: Set<String>,
    markdownDocuments: Map<String, String>,
): List<String> {
    val linkPattern = Regex("(?<!!)\\[[^]\\n]*]\\(([^)\\n]+)\\)")
    val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    val forbiddenDirectories =
        setOf(
            "archive",
            "archives",
            "completed-plans",
            "history",
            "report",
            "reports",
            "transcript",
            "transcripts",
        )
    val forbiddenFileName =
        Regex(
            "(?:^|[-_])(?:audit-report|subagent-report|raw-report|transcript|completed-plan|closed-plan)(?:[-_]|$)"
        )
    val failures = mutableListOf<String>()

    repositoryPaths
        .filter { it.startsWith("context/") }
        .sorted()
        .forEach { path ->
            val relative = path.removePrefix("context/")
            val segments = relative.split('/')
            val lowerSegments = segments.map(String::lowercase)
            val stem = segments.last().substringBeforeLast('.').lowercase()
            if (
                lowerSegments.dropLast(1).any(forbiddenDirectories::contains) ||
                    forbiddenFileName.containsMatchIn(stem)
            ) {
                failures += "$path is a forbidden historical context location"
            }
        }

    val reachable = mutableSetOf("AGENTS.md", "context/README.md")
    val pending = ArrayDeque<String>()
    pending.addAll(reachable)
    while (pending.isNotEmpty()) {
        val source = pending.removeFirst()
        val text = markdownDocuments[source] ?: continue
        linkPattern.findAll(text).forEach { match ->
            val rawTarget = match.groupValues[1].trim().removeSurrounding("<", ">")
            val target = rawTarget.substringBefore('#')
            if (
                target.isBlank() ||
                    rawTarget.startsWith('#') ||
                    rawTarget.startsWith("//") ||
                    schemePattern.containsMatchIn(rawTarget)
            ) {
                return@forEach
            }
            val parent = Path.of(source).parent ?: Path.of("")
            val resolved =
                parent.resolve(target).normalize().toString().replace(File.separatorChar, '/')
            if (resolved in repositoryPaths && reachable.add(resolved)) {
                pending.addLast(resolved)
            }
        }
    }

    repositoryPaths
        .filter { it.startsWith("context/") && it !in reachable }
        .sorted()
        .forEach { path -> failures += "$path is unreachable from AGENTS.md or context/README.md" }
    return failures
}

val contextHygieneSelfTest =
    tasks.register("contextHygieneSelfTest") {
        description = "Proves that context hygiene rejects orphaned and historical material."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            val cleanPaths = setOf("AGENTS.md", "context/README.md", "context/active.md")
            val cleanDocuments =
                mapOf(
                    "AGENTS.md" to "[Context](context/README.md)",
                    "context/README.md" to "[Active](active.md)",
                    "context/active.md" to "# Active",
                )
            check(contextHygieneFailures(cleanPaths, cleanDocuments).isEmpty()) {
                "Context hygiene self-test rejected a reachable active document"
            }

            val orphanFailures =
                contextHygieneFailures(
                    cleanPaths + "context/orphan.md",
                    cleanDocuments + ("context/orphan.md" to "# Orphan"),
                )
            check(orphanFailures.any { it.contains("context/orphan.md is unreachable") }) {
                "Context hygiene self-test accepted an orphaned document"
            }

            for (hostilePath in
                listOf(
                    "context/archive/decision.md",
                    "context/reports/current.md",
                    "context/raw-audit-report.md",
                    "context/session-transcript.md",
                    "context/completed-plan.md",
                )) {
                val hostileDocuments =
                    cleanDocuments +
                        ("context/README.md" to
                            "[Active](active.md)\n[Hostile](${hostilePath.removePrefix("context/")})") +
                        (hostilePath to "# Hostile")
                val failures = contextHygieneFailures(cleanPaths + hostilePath, hostileDocuments)
                check(failures.any { it.contains("forbidden historical context location") }) {
                    "Context hygiene self-test accepted $hostilePath"
                }
            }
        }
    }

val contextHygieneCheck =
    tasks.register("contextHygieneCheck") {
        description =
            "Checks that current context is reachable and contains no historical material."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(contextHygieneSelfTest)

        val contextFiles = fileTree(layout.projectDirectory.dir("context"))
        val entrypoint = layout.projectDirectory.file("AGENTS.md")
        inputs.files(contextFiles, entrypoint)

        doLast {
            val files = (contextFiles.files + entrypoint.asFile).filter(File::isFile)
            val paths = files.map { it.relativeTo(rootDir).invariantSeparatorsPath }.toSet()
            val documents =
                files
                    .filter { it.extension.equals("md", ignoreCase = true) }
                    .associate { it.relativeTo(rootDir).invariantSeparatorsPath to it.readText() }
            val failures = contextHygieneFailures(paths, documents)
            if (failures.isNotEmpty()) {
                throw GradleException("Context hygiene violations:\n${failures.joinToString("\n")}")
            }
        }
    }

val contextLinksCheck =
    tasks.register("contextLinksCheck") {
        description = "Checks local Markdown links in AGENTS.md and project context."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(contextHygieneCheck)

        val markdownFiles =
            files(
                layout.projectDirectory.file("AGENTS.md"),
                fileTree(layout.projectDirectory.dir("context")) { include("**/*.md") },
            )
        inputs.files(markdownFiles)

        doLast {
            val linkPattern = Regex("(?<!!)\\[[^]\\n]*]\\(([^)\\n]+)\\)")
            val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
            val broken = mutableListOf<String>()
            markdownFiles.files.filter(File::isFile).sorted().forEach { source ->
                source.readLines().forEachIndexed { index, line ->
                    linkPattern.findAll(line).forEach { match ->
                        val rawTarget = match.groupValues[1].trim().removeSurrounding("<", ">")
                        val target = rawTarget.substringBefore('#')
                        if (
                            target.isBlank() ||
                                rawTarget.startsWith('#') ||
                                rawTarget.startsWith("//") ||
                                schemePattern.containsMatchIn(rawTarget)
                        ) {
                            return@forEach
                        }
                        if (!source.parentFile.resolve(target).exists()) {
                            broken += "${source.relativeTo(rootDir)}:${index + 1} -> $rawTarget"
                        }
                    }
                }
            }
            if (broken.isNotEmpty()) {
                throw GradleException("Broken local context links:\n${broken.joinToString("\n")}")
            }
        }
    }

tasks.check { dependsOn(contextLinksCheck) }

quickCheck.configure { dependsOn(contextLinksCheck) }

val preparedPublicDocs = layout.buildDirectory.dir("prepared-public-docs")
val kotlinDokkaHtml = project(":procwright-kotlin").layout.buildDirectory.dir("kdoc-validation")

val preparePublicDocs =
    tasks.register<Sync>("preparePublicDocs") {
        description = "Combines public Markdown and generated Java and Kotlin API docs for MkDocs."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(publicJavaJavadocCheck, ":procwright-kotlin:dokkaGeneratePublicationHtml")

        from(layout.projectDirectory.dir("docs"))
        from(layout.buildDirectory.dir("docs/javadoc")) {
            exclude("**/*.md")
            into("api/java/core")
        }
        from(project(":procwright-integrations").layout.buildDirectory.dir("docs/javadoc")) {
            exclude("**/*.md")
            into("api/java/integrations")
        }
        from(kotlinDokkaHtml) { into("api/kotlin") }
        into(preparedPublicDocs)
    }

val publicDocsCheck =
    tasks.register<Exec>("publicDocsCheck") {
        description =
            "Builds the public MkDocs documentation site in strict mode and attaches generated API docs."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(preparePublicDocs, docsRequirementsLockCheck)

        val requirements = layout.projectDirectory.file("docs/requirements.lock")
        val requirementsSource = layout.projectDirectory.file("docs/requirements.txt")
        val config = layout.projectDirectory.file("mkdocs.yml")
        val output = layout.buildDirectory.dir("public-docs")

        inputs.file(requirements)
        inputs.file(requirementsSource)
        inputs.file(config)
        inputs.dir(layout.projectDirectory.dir("docs"))
        inputs.dir(preparedPublicDocs)
        outputs.dir(output)
        environment("PROCWRIGHT_DOCS_DIR", preparedPublicDocs.get().asFile.absolutePath)

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
    }

private fun Element.directChild(name: String): Element? =
    (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().firstOrNull {
        child ->
        child.tagName == name
    }

private fun Element.requiredText(name: String, source: File): String =
    directChild(name)?.textContent?.trim()?.takeIf(String::isNotEmpty)
        ?: throw GradleException("Generated POM is missing $name: $source")

val publicationStructureCheck =
    tasks.register("publicationStructureCheck") {
        description =
            "Checks registry-independent artifact and POM structure for every public module."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        val publications =
            mapOf(
                ":" to "procwright",
                ":procwright-integrations" to "procwright-integrations",
                ":procwright-kotlin" to "procwright-kotlin",
            )
        publications.keys.forEach { projectPath ->
            val prefix = if (projectPath == ":") ":" else "$projectPath:"
            dependsOn(
                "${prefix}jar",
                "${prefix}sourcesJar",
                "${prefix}javadocJar",
                "${prefix}generatePomFileForMavenJavaPublication",
            )
        }

        doLast {
            publications.forEach { (projectPath, artifactId) ->
                val checkedProject = project(projectPath)
                val publication =
                    checkedProject.extensions
                        .getByType(PublishingExtension::class.java)
                        .publications
                        .getByName("mavenJava") as MavenPublication
                val classifiers =
                    publication.artifacts.map { artifact -> artifact.classifier }.toSet()
                if (
                    publication.artifacts.size != 3 ||
                        classifiers != setOf(null, "sources", "javadoc")
                ) {
                    throw GradleException(
                        "$projectPath publication must contain main, sources, and javadoc artifacts: $classifiers"
                    )
                }
                publication.artifacts.forEach { artifact ->
                    if (artifact.extension != "jar") {
                        throw GradleException(
                            "Publication artifact must be a JAR: ${artifact.file}"
                        )
                    }
                    if (!artifact.file.isFile || artifact.file.length() == 0L) {
                        throw GradleException(
                            "Publication artifact is missing or empty: ${artifact.file}"
                        )
                    }
                }

                val pomTask =
                    checkedProject.tasks
                        .named(
                            "generatePomFileForMavenJavaPublication",
                            GenerateMavenPom::class.java,
                        )
                        .get()
                val pomFile = pomTask.destination
                val documentBuilder =
                    DocumentBuilderFactory.newInstance().apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    }
                val root = documentBuilder.newDocumentBuilder().parse(pomFile).documentElement
                if (root.requiredText("groupId", pomFile) != publicMavenGroup) {
                    throw GradleException("Generated POM has the wrong groupId: $pomFile")
                }
                if (root.requiredText("artifactId", pomFile) != artifactId) {
                    throw GradleException("Generated POM has the wrong artifactId: $pomFile")
                }
                root.requiredText("version", pomFile)
                root.requiredText("name", pomFile)
                root.requiredText("description", pomFile)
                root.requiredText("url", pomFile)
                val license =
                    root.directChild("licenses")?.directChild("license")
                        ?: throw GradleException("Generated POM has no license: $pomFile")
                license.requiredText("name", pomFile)
                license.requiredText("url", pomFile)
                val scm =
                    root.directChild("scm")
                        ?: throw GradleException("Generated POM has no SCM: $pomFile")
                scm.requiredText("connection", pomFile)
                scm.requiredText("developerConnection", pomFile)
                scm.requiredText("url", pomFile)
                val developer =
                    root.directChild("developers")?.directChild("developer")
                        ?: throw GradleException("Generated POM has no developer: $pomFile")
                developer.requiredText("id", pomFile)
                developer.requiredText("name", pomFile)
            }
        }
    }

val publicationReadinessCheck =
    tasks.register("publicationReadinessCheck") {
        description = "Runs product, documentation, and API checks required before publication."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            tasks.named("spotlessCheck"),
            regressionCheck,
            publicDocsCheck,
            publicationStructureCheck,
            ":procwright-kotlin:kotlinJSpecifyStrictnessCheck",
        )
    }

spotless {
    java {
        palantirJavaFormat("2.80.0")
        target("src/**/*.java", "procwright-*/src/**/*.java", "docs/examples/**/*.java")
        licenseHeader(
            "/* SPDX-License-Identifier: Apache-2.0 */\n\n",
            "(?:/\\*\\*|package |import |open |module )",
        )
    }
    kotlin {
        ktfmt("0.58").kotlinlangStyle()
        target("procwright-kotlin/src/**/*.kt", "docs/examples/**/*.kt")
        licenseHeader(
            "/* SPDX-License-Identifier: Apache-2.0 */\n\n",
            "(?:/\\*\\*|@file|package |import )",
        )
    }
    kotlinGradle {
        ktfmt("0.58").kotlinlangStyle()
        target("*.gradle.kts", "procwright-*/*.gradle.kts")
    }
}
