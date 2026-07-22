import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.w3c.dom.Document
import org.w3c.dom.Element

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.diffplug.spotless") version "8.8.0"
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

fun releaseCandidateUsesFinalReleaseChecks(version: String): Boolean =
    !version.endsWith("-SNAPSHOT")

val releaseCandidateFinalMode = releaseCandidateUsesFinalReleaseChecks(procwrightVersion)
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
val mavenCentralBundleRepository = layout.buildDirectory.dir("maven-central/repository")
val releaseVersionPattern =
    Regex(
        """(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-(?:(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"""
    )

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

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "MavenCentralBundle"
                    url =
                        rootProject.layout.buildDirectory
                            .dir("maven-central/repository")
                            .get()
                            .asFile
                            .toURI()
                }
            }
        }
    }

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
            if (procwrightJavaRelease != 17) {
                throw GradleException(
                    "Public Procwright artifacts must be published with --project-prop=procwright.javaRelease=17"
                )
            }
            if (procwrightVersion.endsWith("-SNAPSHOT")) {
                throw GradleException(
                    "Public Procwright artifacts must not be published with a SNAPSHOT version; set --project-prop=procwright.version=<release-version>"
                )
            }
            if (!releaseVersionPattern.matches(procwrightVersion)) {
                throw GradleException(
                    "Public Procwright artifacts must use a SemVer release version such as 0.1.0 or 0.1.0-rc.1"
                )
            }
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

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (!signingKey.isNullOrBlank() && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}

dependencies {
    compileOnlyApi("org.jspecify:jspecify:1.0.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
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

    val releaseCheck by creating

    val releaseCheckTest by creating {
        compileClasspath += releaseCheck.output
        compileClasspath += configurations.getByName("releaseCheckRuntimeClasspath")
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

configurations.named("releaseCheckTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named("releaseCheckTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    "integrationTestImplementation"(project(":procwright-test-cli"))
    "stressTestImplementation"(project(":procwright-test-cli"))
    "apiCompatibilityImplementation"("org.jspecify:jspecify:1.0.0")
    "releaseCheckImplementation"("org.snakeyaml:snakeyaml-engine:3.0.1")
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

val releaseWorkflowStaticTest =
    tasks.register<Test>("releaseWorkflowStaticTest") {
        description = "Runs hostile-fixture tests for the structured release workflow validator."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = sourceSets["releaseCheckTest"].output.classesDirs
        classpath = sourceSets["releaseCheckTest"].runtimeClasspath
        systemProperty("procwright.repositoryRoot", rootDir.absolutePath)
    }

tasks.check { dependsOn(integrationTest, stressTest) }

val publicRuntimeProjects = setOf(project.path, ":procwright-kotlin", ":procwright-integrations")
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
            "Verifies public-module dependency boundaries, including compile-only JSpecify metadata."
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
                                    dependency.path == ":procwright-comparison"
                            ) {
                                throw GradleException(
                                    "Public project ${checkedProject.path} must not depend on :procwright-comparison"
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

tasks.check { dependsOn(":procwright-test-cli:check") }

val consumerExampleCheckPaths =
    listOf(
        ":procwright-consumer-examples:check",
        ":procwright-integrations-consumer-example:check",
        ":procwright-kotlin-consumer-example:check",
    )

tasks.check { dependsOn(consumerExampleCheckPaths) }

val publicApiConsumerCompilationCheck =
    tasks.register("publicApiConsumerCompilationCheck") {
        description =
            "Compiles every in-repository public API consumer: root stress sources, comparison and integrations tests, the Kotlin module tests, and the Java, integrations, and Kotlin canonical consumer examples."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            tasks.named("compileStressTestJava"),
            ":procwright-comparison:compileTestJava",
            ":procwright-integrations:compileTestJava",
            ":procwright-kotlin:compileTestKotlin",
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
            "apiCompatibilitySelfTest",
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

        doLast {
            assertStrictJavadocOptions(
                layout.buildDirectory.file("tmp/javadoc/javadoc.options").get().asFile,
                "core",
            )
            assertStrictJavadocOptions(
                project(":procwright-integrations")
                    .layout
                    .buildDirectory
                    .file("tmp/javadoc/javadoc.options")
                    .get()
                    .asFile,
                "integrations",
            )
            assertGeneratedJavadocMemberTerms(
                layout.buildDirectory
                    .file("docs/javadoc/io/github/ulviar/procwright/session/ProtocolAdapter.html")
                    .get()
                    .asFile,
                "writeRequest(I,io.github.ulviar.procwright.session.ProtocolWriter)",
                listOf("FAILURE"),
            )
            assertGeneratedJavadocMemberTerms(
                layout.buildDirectory
                    .file("docs/javadoc/io/github/ulviar/procwright/session/ProtocolAdapter.html")
                    .get()
                    .asFile,
                "readResponse(io.github.ulviar.procwright.session.ProtocolReaders)",
                listOf("PROTOCOL_DECODER_FAILED"),
            )
            assertGeneratedJavadocMemberTerms(
                layout.buildDirectory
                    .file("docs/javadoc/io/github/ulviar/procwright/session/ResponseDecoder.html")
                    .get()
                    .asFile,
                "decode(io.github.ulviar.procwright.session.ResponseDecoder.Reader)",
                listOf("DECODER_FAILED"),
            )
            assertJavadocMemberGateIgnoresUnrelatedPageText()
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

fun assertGeneratedJavadocMemberTerms(
    document: File,
    memberAnchor: String,
    requiredTerms: List<String>,
) {
    if (!document.isFile) {
        throw GradleException("Generated Javadoc is missing for $memberAnchor: $document")
    }
    val memberSection = javadocMemberSection(document.readText(), memberAnchor)
    val missing = requiredTerms.filterNot(memberSection::contains)
    if (missing.isNotEmpty()) {
        throw GradleException(
            "Generated Javadoc member $memberAnchor is missing contract terms: $missing"
        )
    }
}

fun javadocMemberSection(document: String, memberAnchor: String): String {
    val startMarker = "<section class=\"detail\" id=\"$memberAnchor\">"
    val start = document.indexOf(startMarker)
    if (start < 0) {
        throw GradleException("Generated Javadoc member anchor is missing: $memberAnchor")
    }
    val end = document.indexOf("</section>", start + startMarker.length)
    if (end < 0) {
        throw GradleException("Generated Javadoc member section is incomplete: $memberAnchor")
    }
    return document.substring(start, end + "</section>".length)
}

fun assertJavadocMemberGateIgnoresUnrelatedPageText() {
    val fixture =
        """<p>FAILURE</p><section class="detail" id="target()"><p>member documentation</p></section>"""
    if (javadocMemberSection(fixture, "target()").contains("FAILURE")) {
        throw GradleException(
            "Javadoc member gate accepted contract text from outside the target declaration"
        )
    }
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
                    "io.github.ulviar.procwright.preset",
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

val apiCompatibilitySelfTest =
    tasks.register<JavaExec>("apiCompatibilitySelfTest") {
        description = "Proves that exact API signatures reject hostile source-contract changes."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named(sourceSets["apiCompatibility"].classesTaskName))
        classpath = sourceSets["apiCompatibility"].runtimeClasspath
        mainClass.set(apiCompatibilityMainClass)
        args("self-test")
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

fun releaseDocsContentFailures(
    releaseVersion: String,
    sources: List<Pair<String, String>>,
): List<String> {
    val preReleaseStatement =
        Regex(
            "no (?:supported )?public release|no release is available|not yet on maven central|before the first public release|planned first release|first release will",
            RegexOption.IGNORE_CASE,
        )
    val movingSourceLink = Regex("https://github\\.com/Ulviar/Procwright/(?:blob|tree)/main/")
    val gradleCoordinate =
        Regex("io\\.github\\.ulviar:procwright(?:-[a-z]+)?:([0-9][0-9A-Za-z.+-]*)")
    val mavenCoordinate =
        Regex(
            "<artifactId>procwright(?:-[a-z]+)?</artifactId>\\s*<version>([^<]+)</version>",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    val failures = mutableListOf<String>()

    sources.forEach { (source, text) ->
        preReleaseStatement.findAll(text).forEach { match ->
            failures += "$source still contains pre-release text: ${match.value}"
        }
        movingSourceLink.findAll(text).forEach { match ->
            failures += "$source uses a moving release source link: ${match.value}"
        }
        gradleCoordinate.findAll(text).forEach { match ->
            if (match.groupValues[1] != releaseVersion) {
                failures += "$source uses ${match.value}, expected version $releaseVersion"
            }
        }
        mavenCoordinate.findAll(text).forEach { match ->
            if (match.groupValues[1].trim() != releaseVersion) {
                failures +=
                    "$source uses Maven version ${match.groupValues[1].trim()}, expected $releaseVersion"
            }
        }
    }
    return failures
}

val releaseDocsContentCheck =
    tasks.register("releaseDocsContentCheck") {
        description =
            "Rejects unreleased status, moving source links, and wrong coordinates in release docs."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        val releaseMarkdown =
            files(
                layout.projectDirectory.file("README.md"),
                fileTree(layout.projectDirectory.dir("docs")) { include("**/*.md") },
            )
        inputs.files(releaseMarkdown)
        inputs.property("releaseVersion", procwrightVersion)
        onlyIf { releaseCandidateFinalMode }

        doLast {
            val failures =
                releaseDocsContentFailures(
                    procwrightVersion,
                    releaseMarkdown.files.filter(File::isFile).sorted().map { source ->
                        source.relativeTo(rootDir).invariantSeparatorsPath to source.readText()
                    },
                )
            if (failures.isNotEmpty()) {
                throw GradleException(
                    "Release documentation is not ready:\n${failures.joinToString("\n")}"
                )
            }
        }
    }

val releaseDocsContentSelfTest =
    tasks.register("releaseDocsContentSelfTest") {
        description =
            "Proves that release docs reject pre-release status, moving links, and wrong coordinates."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            val selectedVersion = "1.2.3"
            val finalized =
                """
                # Procwright
                implementation("io.github.ulviar:procwright:$selectedVersion")
                https://github.com/Ulviar/Procwright/blob/v$selectedVersion/README.md
                """
                    .trimIndent()
            if (
                releaseDocsContentFailures(selectedVersion, listOf("README.md" to finalized))
                    .isNotEmpty()
            ) {
                throw GradleException("Finalized release documentation fixture must pass")
            }

            val rejected =
                listOf(
                    "No public release exists yet.",
                    "implementation(\"io.github.ulviar:procwright:9.9.9\")",
                    "https://github.com/Ulviar/Procwright/blob/main/README.md",
                )
            rejected.forEach { text ->
                if (
                    releaseDocsContentFailures(selectedVersion, listOf("README.md" to text))
                        .isEmpty()
                ) {
                    throw GradleException(
                        "Release documentation check accepted invalid fixture: $text"
                    )
                }
            }
        }
    }

val canonicalPublicDocsUrlCheck =
    tasks.register("canonicalPublicDocsUrlCheck") {
        description = "Checks that the public documentation site is canonical and discoverable."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        val readme = layout.projectDirectory.file("README.md")
        val mkdocs = layout.projectDirectory.file("mkdocs.yml")
        val canonicalUrl = "https://ulviar.github.io/Procwright/"
        inputs.files(readme, mkdocs)
        inputs.property("canonicalUrl", canonicalUrl)

        doLast {
            val failures = mutableListOf<String>()
            if (
                !mkdocs.asFile.readLines().any { line -> line.trim() == "site_url: $canonicalUrl" }
            ) {
                failures += "mkdocs.yml must declare site_url: $canonicalUrl"
            }
            if (!readme.asFile.readText().contains("]($canonicalUrl)")) {
                failures += "README.md must link to $canonicalUrl"
            }
            if (failures.isNotEmpty()) {
                throw GradleException(failures.joinToString("\n"))
            }
        }
    }

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
        dependsOn(preparePublicDocs, docsRequirementsLockCheck, canonicalPublicDocsUrlCheck)
        if (releaseCandidateFinalMode) {
            dependsOn(releaseDocsContentCheck)
        }

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

        doLast {
            listOf(
                    Triple(
                        "core Java API",
                        output.get().asFile.resolve("api/java/core/index.html"),
                        listOf(
                            "<title>Overview (procwright ",
                            "href=\"io/github/ulviar/procwright/package-summary.html\"",
                        ),
                    ),
                    Triple(
                        "integrations Java API",
                        output.get().asFile.resolve("api/java/integrations/index.html"),
                        listOf(
                            "<title>procwright-integrations ",
                            "href=\"io.github.ulviar.procwright.integrations/module-summary.html\"",
                        ),
                    ),
                    Triple(
                        "Kotlin API",
                        output.get().asFile.resolve("api/kotlin/index.html"),
                        listOf(
                            "<title>procwright-kotlin</title>",
                            "href=\"procwright-kotlin/io.github.ulviar.procwright.kotlin/index.html\"",
                        ),
                    ),
                )
                .forEach { (apiName, apiIndex, identityMarkers) ->
                    assertGeneratedApiRoute(apiName, apiIndex, identityMarkers)
                }
        }
    }

fun assertGeneratedApiRoute(apiName: String, document: File, identityMarkers: List<String>) {
    if (!document.isFile) {
        throw GradleException("Generated $apiName index is missing: $document")
    }
    val contents = document.readText()
    val missingMarkers = identityMarkers.filterNot(contents::contains)
    if (missingMarkers.isNotEmpty()) {
        throw GradleException(
            "Generated $apiName route does not identify the expected API: " +
                "$document (missing $missingMarkers)"
        )
    }
}

data class VerificationComponentId(val group: String, val name: String, val version: String) :
    Comparable<VerificationComponentId> {
    override fun compareTo(other: VerificationComponentId): Int =
        compareValuesBy(this, other, { it.group }, { it.name }, { it.version })

    override fun toString(): String = "$group:$name:$version"
}

data class VerificationHash(val algorithm: String, val value: String) :
    Comparable<VerificationHash> {
    override fun compareTo(other: VerificationHash): Int =
        compareValuesBy(this, other, { it.algorithm }, { it.value })
}

data class VerificationComponent(val artifacts: Map<String, Set<VerificationHash>>)

data class VerificationMetadataSnapshot(
    val document: Document,
    val envelope: String,
    val componentsElement: Element,
    val components: Map<VerificationComponentId, VerificationComponent>,
    val componentElements: Map<VerificationComponentId, Element>,
)

fun Element.localElementName(): String = localName ?: tagName.substringAfter(':')

fun Element.directChildElements(): List<Element> {
    val result = mutableListOf<Element>()
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element) {
            result += child
        }
    }
    return result
}

fun canonicalVerificationElement(element: Element, omitDirectChild: String? = null): String =
    buildString {
        append('<').append(element.localElementName())
        val attributes = element.attributes
        (0 until attributes.length)
            .map { index -> attributes.item(index) }
            .sortedBy { attribute -> "${attribute.namespaceURI}:${attribute.nodeName}" }
            .forEach { attribute ->
                append(' ').append(attribute.nodeName).append('=').append(attribute.nodeValue)
            }
        append('>')
        element.directChildElements().forEach { child ->
            if (child.localElementName() != omitDirectChild) {
                append(canonicalVerificationElement(child))
            }
        }
        val text =
            (0 until element.childNodes.length)
                .map { index -> element.childNodes.item(index) }
                .filter { child -> child.nodeType == org.w3c.dom.Node.TEXT_NODE }
                .joinToString(separator = "") { child -> child.nodeValue }
                .trim()
        if (text.isNotEmpty()) {
            append(text)
        }
        append("</").append(element.localElementName()).append('>')
    }

fun parseVerificationMetadata(file: File): VerificationMetadataSnapshot {
    if (!file.isFile) {
        throw GradleException("Dependency verification metadata is missing: $file")
    }
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
    val document = factory.newDocumentBuilder().parse(file)
    val root = document.documentElement
    if (root.localElementName() != "verification-metadata") {
        throw GradleException("Unexpected dependency verification root in $file")
    }
    val componentsElements =
        root.directChildElements().filter { child -> child.localElementName() == "components" }
    if (componentsElements.size != 1) {
        throw GradleException("$file must contain exactly one components element")
    }
    val componentElements = linkedMapOf<VerificationComponentId, Element>()
    val components = linkedMapOf<VerificationComponentId, VerificationComponent>()
    componentsElements.single().directChildElements().forEach { component ->
        if (component.localElementName() != "component") {
            throw GradleException(
                "Unexpected ${component.localElementName()} element under components in $file"
            )
        }
        val id =
            VerificationComponentId(
                component.getAttribute("group"),
                component.getAttribute("name"),
                component.getAttribute("version"),
            )
        if (id.group.isBlank() || id.name.isBlank() || id.version.isBlank()) {
            throw GradleException("Incomplete component coordinate in $file")
        }
        val artifacts = linkedMapOf<String, Set<VerificationHash>>()
        component.directChildElements().forEach { artifact ->
            if (artifact.localElementName() != "artifact") {
                throw GradleException(
                    "Unexpected ${artifact.localElementName()} element under $id in $file"
                )
            }
            val artifactName = artifact.getAttribute("name")
            if (artifactName.isBlank()) {
                throw GradleException("Unnamed artifact under $id in $file")
            }
            val hashes =
                artifact
                    .directChildElements()
                    .map { checksum ->
                        val value = checksum.getAttribute("value")
                        if (value.isBlank()) {
                            throw GradleException(
                                "Checksum without a value for $id/$artifactName in $file"
                            )
                        }
                        VerificationHash(checksum.localElementName(), value)
                    }
                    .toSet()
            if (hashes.isEmpty()) {
                throw GradleException("Artifact $id/$artifactName has no checksums in $file")
            }
            if (artifacts.put(artifactName, hashes) != null) {
                throw GradleException("Duplicate artifact $id/$artifactName in $file")
            }
        }
        if (artifacts.isEmpty()) {
            throw GradleException("Component $id has no artifacts in $file")
        }
        if (components.put(id, VerificationComponent(artifacts)) != null) {
            throw GradleException("Duplicate component $id in $file")
        }
        componentElements[id] = component
    }
    return VerificationMetadataSnapshot(
        document,
        canonicalVerificationElement(root, "components"),
        componentsElements.single(),
        components,
        componentElements,
    )
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

fun requireDirectRegularFileNoFollow(rootDirectory: File, fileName: String): Path {
    val absoluteRoot = rootDirectory.toPath().toAbsolutePath()
    val root = absoluteRoot.normalize()
    if (absoluteRoot != root) {
        throw GradleException("Verification evidence root must be normalized: $absoluteRoot")
    }
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        throw GradleException("Verification evidence root must be a non-symlink directory: $root")
    }
    val file = root.resolve(fileName).normalize()
    if (file.parent != root || file.fileName?.toString() != fileName) {
        throw GradleException(
            "Verification evidence must be a normalized direct child of $root: $fileName"
        )
    }
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw GradleException("Verification evidence must be a non-symlink regular file: $file")
    }
    return file
}

fun sha256HexNoFollow(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    Files.newByteChannel(file, options).use { channel ->
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        while (channel.read(buffer) >= 0) {
            buffer.flip()
            digest.update(buffer)
            buffer.clear()
        }
    }
    return digest.digest().toHexString()
}

fun mergeCentralConsumerVerificationMetadata(
    baselineFile: File,
    normalCandidateFile: File,
    pomCandidateFile: File,
    verifiedArtifactsDirectory: File,
    releaseVersion: String,
    outputFile: File,
) {
    if (!releaseVersionPattern.matches(releaseVersion)) {
        throw GradleException("Invalid Central consumer version: $releaseVersion")
    }
    val allowedModules = setOf("procwright", "procwright-integrations", "procwright-kotlin")
    val allowedIds =
        allowedModules
            .map { module -> VerificationComponentId(publicMavenGroup, module, releaseVersion) }
            .toSet()
    val allowedSuffixes = setOf(".jar", ".pom", ".module", "-sources.jar", "-javadoc.jar")
    val baseline = parseVerificationMetadata(baselineFile)
    val candidates =
        listOf(
            "normal" to parseVerificationMetadata(normalCandidateFile),
            "mavenPom" to parseVerificationMetadata(pomCandidateFile),
        )
    val mergedReleaseComponents = linkedMapOf<VerificationComponentId, VerificationComponent>()

    candidates.forEach { (mode, candidate) ->
        if (candidate.envelope != baseline.envelope) {
            throw GradleException(
                "$mode verification candidate changed verification configuration or root metadata"
            )
        }
        baseline.components.forEach { (id, baselineComponent) ->
            val candidateComponent =
                candidate.components[id]
                    ?: throw GradleException("$mode verification candidate removed $id")
            val baselineArtifacts = baselineComponent.artifacts
            val candidateArtifacts = candidateComponent.artifacts
            baselineArtifacts.forEach { (artifact, hashes) ->
                if (candidateArtifacts[artifact] != hashes) {
                    throw GradleException("$mode verification candidate changed $id/$artifact")
                }
            }
            if (id !in allowedIds && candidateArtifacts != baselineArtifacts) {
                throw GradleException(
                    "$mode verification candidate added metadata to unrelated component $id"
                )
            }
        }
        val addedIds = candidate.components.keys - baseline.components.keys
        val unrelatedIds = addedIds - allowedIds
        if (unrelatedIds.isNotEmpty()) {
            throw GradleException(
                "$mode verification candidate added unrelated components: ${
                    unrelatedIds.sorted().joinToString()
                }"
            )
        }
        val missingReleaseIds = allowedIds - candidate.components.keys
        if (missingReleaseIds.isNotEmpty()) {
            throw GradleException(
                "$mode verification candidate did not resolve: ${
                    missingReleaseIds.sorted().joinToString()
                }"
            )
        }

        allowedIds.sorted().forEach { id ->
            val component = candidate.components.getValue(id)
            val requiredSuffixes =
                when (mode) {
                    "normal" -> setOf(".jar", ".module")
                    "mavenPom" -> setOf(".jar", ".pom")
                    else -> throw GradleException("Unsupported verification candidate mode: $mode")
                }
            val requiredArtifacts =
                requiredSuffixes.map { suffix -> "${id.name}-$releaseVersion$suffix" }.toSet()
            val missingArtifacts = requiredArtifacts - component.artifacts.keys
            if (missingArtifacts.isNotEmpty()) {
                throw GradleException(
                    "$mode verification candidate is missing byte-verified artifacts for $id: ${
                        missingArtifacts.sorted().joinToString()
                    }"
                )
            }

            component.artifacts.forEach { (artifactName, hashes) ->
                val expectedNames =
                    allowedSuffixes.map { suffix -> "${id.name}-$releaseVersion$suffix" }.toSet()
                if (artifactName !in expectedNames) {
                    throw GradleException(
                        "$mode verification candidate added unexpected release artifact $id/$artifactName"
                    )
                }
                if (
                    hashes.size != 1 ||
                        hashes.single().algorithm != "sha256" ||
                        !Regex("[0-9a-f]{64}").matches(hashes.single().value)
                ) {
                    throw GradleException(
                        "$mode verification candidate must provide one lowercase SHA-256 for $id/$artifactName"
                    )
                }
                val verifiedArtifact =
                    requireDirectRegularFileNoFollow(verifiedArtifactsDirectory, artifactName)
                val actualHash = sha256HexNoFollow(verifiedArtifact)
                if (hashes.single().value != actualHash) {
                    throw GradleException(
                        "$mode verification candidate checksum does not match byte-verified Central artifact $id/$artifactName"
                    )
                }
            }

            val previous = mergedReleaseComponents[id]
            val mergedArtifacts = previous?.artifacts?.toMutableMap() ?: linkedMapOf()
            component.artifacts.forEach { (artifactName, hashes) ->
                val previousHashes = mergedArtifacts.putIfAbsent(artifactName, hashes)
                if (previousHashes != null && previousHashes != hashes) {
                    throw GradleException(
                        "Verification candidates disagree about $id/$artifactName"
                    )
                }
            }
            mergedReleaseComponents[id] = VerificationComponent(mergedArtifacts)
        }
    }

    val document = baseline.document
    val namespace = document.documentElement.namespaceURI
    val mergedElements = baseline.componentElements.toMutableMap()
    mergedReleaseComponents.forEach { (id, component) ->
        val componentElement = document.createElementNS(namespace, "component")
        componentElement.setAttribute("group", id.group)
        componentElement.setAttribute("name", id.name)
        componentElement.setAttribute("version", id.version)
        component.artifacts.toSortedMap().forEach { (artifactName, hashes) ->
            val artifactElement = document.createElementNS(namespace, "artifact")
            artifactElement.setAttribute("name", artifactName)
            hashes.sorted().forEach { hash ->
                val checksumElement = document.createElementNS(namespace, hash.algorithm)
                checksumElement.setAttribute("value", hash.value)
                checksumElement.setAttribute("origin", "Verified against staged and Central bytes")
                artifactElement.appendChild(checksumElement)
            }
            componentElement.appendChild(artifactElement)
        }
        mergedElements[id] = componentElement
    }
    while (baseline.componentsElement.hasChildNodes()) {
        baseline.componentsElement.removeChild(baseline.componentsElement.firstChild)
    }
    mergedElements.toSortedMap().values.forEach { component ->
        baseline.componentsElement.appendChild(component)
    }

    outputFile.parentFile.mkdirs()
    val temporary = Files.createTempFile(outputFile.parentFile.toPath(), ".verification-", ".xml")
    try {
        Files.newOutputStream(temporary).use { output ->
            val transformer =
                TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                    setOutputProperty(OutputKeys.INDENT, "yes")
                    setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "3")
                }
            transformer.transform(DOMSource(document), StreamResult(output))
        }
        Files.move(
            temporary,
            outputFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        Files.deleteIfExists(temporary)
    }
}

val mergeCentralConsumerVerificationMetadata =
    tasks.register("mergeCentralConsumerVerificationMetadata") {
        description =
            "Fail-closed merges byte-verified Procwright release checksums from normal and POM-only candidates."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn("centralConsumerVerificationMetadataSelfTest")

        doLast {
            fun requiredProperty(name: String): String =
                providers.gradleProperty(name).orNull?.takeIf(String::isNotBlank)
                    ?: throw GradleException("Missing required Gradle property: $name")

            val baseline =
                providers
                    .gradleProperty("procwright.verificationBaseline")
                    .orElse("gradle/verification-metadata.xml")
                    .get()
            mergeCentralConsumerVerificationMetadata(
                file(baseline),
                file(requiredProperty("procwright.verificationCandidateNormal")),
                file(requiredProperty("procwright.verificationCandidatePom")),
                file(requiredProperty("procwright.verificationArtifacts")),
                requiredProperty("procwright.consumerVersion"),
                file(baseline),
            )
        }
    }

val centralConsumerVerificationMetadataSelfTest =
    tasks.register("centralConsumerVerificationMetadataSelfTest") {
        description =
            "Proves the Central verification merge accepts only byte-matched release artifacts."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            val fixtureDirectory =
                Files.createTempDirectory(temporaryDir.toPath(), "fixture-").toFile()
            val verifiedDirectory = File(fixtureDirectory, "verified").apply { mkdirs() }
            val version = "1.2.3"
            val modules = listOf("procwright", "procwright-integrations", "procwright-kotlin")
            val evidenceHashes = linkedMapOf<String, String>()
            modules.forEach { module ->
                listOf(".jar", ".module", ".pom").forEach { suffix ->
                    val artifact = File(verifiedDirectory, "$module-$version$suffix")
                    artifact.writeText("$module$suffix verified bytes\n")
                    evidenceHashes[artifact.name] = sha256Hex(artifact)
                }
            }
            val staticHash = "1".repeat(64)
            val staticComponent =
                """
                <component group="org.example" name="reviewed-parent" version="1">
                   <artifact name="reviewed-parent-1.pom">
                      <sha256 value="$staticHash" origin="Reviewed static checksum"/>
                   </artifact>
                </component>
                """
                    .trimIndent()

            fun releaseComponents(suffixes: List<String>): String =
                modules.joinToString("\n") { module ->
                    val artifacts =
                        suffixes.joinToString("\n") { suffix ->
                            val artifactName = "$module-$version$suffix"
                            """
                            <artifact name="$artifactName">
                               <sha256 value="${evidenceHashes.getValue(artifactName)}" origin="Generated by Gradle"/>
                            </artifact>
                            """
                                .trimIndent()
                                .prependIndent("   ")
                        }
                    """
                    <component group="$publicMavenGroup" name="$module" version="$version">
                    $artifacts
                    </component>
                    """
                        .trimIndent()
                }

            fun metadata(components: String): String =
                """
                <verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
                   <configuration>
                      <verify-metadata>true</verify-metadata>
                      <verify-signatures>false</verify-signatures>
                   </configuration>
                   <components>
                ${components.prependIndent("      ")}
                   </components>
                </verification-metadata>
                """
                    .trimIndent()
                    .plus("\n")

            val baseline = File(fixtureDirectory, "baseline.xml")
            val normal = File(fixtureDirectory, "normal.xml")
            val pom = File(fixtureDirectory, "pom.xml")
            baseline.writeText(metadata(staticComponent))
            normal.writeText(
                metadata("$staticComponent\n${releaseComponents(listOf(".jar", ".module"))}")
            )
            pom.writeText(
                metadata("$staticComponent\n${releaseComponents(listOf(".jar", ".pom"))}")
            )
            val baselineSnapshot = parseVerificationMetadata(baseline)
            val expectedReleaseIds =
                modules
                    .map { module -> VerificationComponentId(publicMavenGroup, module, version) }
                    .toSet()
            listOf("normal" to normal, "mavenPom" to pom).forEach { (mode, candidateFile) ->
                val candidate = parseVerificationMetadata(candidateFile)
                val candidateDelta = candidate.components.keys - baselineSnapshot.components.keys
                if (candidateDelta != expectedReleaseIds) {
                    throw GradleException(
                        "$mode fixture delta must contain exactly the three release coordinates: $candidateDelta"
                    )
                }
            }

            val merged = File(fixtureDirectory, "merged.xml")
            val mergedAgain = File(fixtureDirectory, "merged-again.xml")
            mergeCentralConsumerVerificationMetadata(
                baseline,
                normal,
                pom,
                verifiedDirectory,
                version,
                merged,
            )
            mergeCentralConsumerVerificationMetadata(
                baseline,
                normal,
                pom,
                verifiedDirectory,
                version,
                mergedAgain,
            )
            if (merged.readBytes().contentEquals(mergedAgain.readBytes()).not()) {
                throw GradleException("Central verification merge output is not deterministic")
            }
            val mergedSnapshot = parseVerificationMetadata(merged)
            val mergedDelta = mergedSnapshot.components.keys - baselineSnapshot.components.keys
            if (mergedDelta != expectedReleaseIds) {
                throw GradleException(
                    "Central verification merge delta must contain exactly the three release coordinates: $mergedDelta"
                )
            }

            fun expectRejected(
                label: String,
                normalCandidate: File,
                pomCandidate: File = pom,
                evidenceDirectory: File = verifiedDirectory,
            ) {
                val protectedBaseline = File(fixtureDirectory, "$label-baseline.xml")
                baseline.copyTo(protectedBaseline, overwrite = true)
                val before = protectedBaseline.readBytes()
                val rejected =
                    runCatching {
                            mergeCentralConsumerVerificationMetadata(
                                protectedBaseline,
                                normalCandidate,
                                pomCandidate,
                                evidenceDirectory,
                                version,
                                protectedBaseline,
                            )
                        }
                        .isFailure
                if (!rejected) {
                    throw GradleException("Central verification self-test accepted $label")
                }
                if (!before.contentEquals(protectedBaseline.readBytes())) {
                    throw GradleException(
                        "Rejected $label changed the protected verification baseline"
                    )
                }
            }

            val unrelated = File(fixtureDirectory, "unrelated.xml")
            unrelated.writeText(
                normal
                    .readText()
                    .replace(
                        "</components>",
                        """
                        <component group="org.unreviewed" name="surprise" version="1">
                           <artifact name="surprise-1.pom">
                              <sha256 value="${"2".repeat(64)}" origin="Generated by Gradle"/>
                           </artifact>
                        </component>
                        </components>
                        """
                            .trimIndent(),
                    )
            )
            expectRejected("unrelated addition", unrelated)

            val removedBaseline = File(fixtureDirectory, "removed-baseline.xml")
            removedBaseline.writeText(normal.readText().replace(staticComponent, ""))
            expectRejected("baseline removal", removedBaseline)

            val changedBaseline = File(fixtureDirectory, "changed-baseline.xml")
            changedBaseline.writeText(normal.readText().replace(staticHash, "3".repeat(64)))
            expectRejected("baseline change", changedBaseline)

            val expandedStaticComponent =
                staticComponent.replace(
                    "</component>",
                    """
                       <artifact name="unreviewed-extra-1.pom">
                          <sha256 value="${"5".repeat(64)}" origin="Generated by Gradle"/>
                       </artifact>
                    </component>
                    """
                        .trimIndent(),
                )
            val expandedBaseline = File(fixtureDirectory, "expanded-baseline.xml")
            expandedBaseline.writeText(
                normal.readText().replace(staticComponent, expandedStaticComponent)
            )
            expectRejected("baseline artifact addition", expandedBaseline)

            val mismatchedBytes = File(fixtureDirectory, "mismatched-bytes.xml")
            val firstEvidenceHash = evidenceHashes.values.first()
            mismatchedBytes.writeText(normal.readText().replace(firstEvidenceHash, "4".repeat(64)))
            expectRejected("byte mismatch", mismatchedBytes)
            expectRejected(
                "non-normalized evidence root",
                normal,
                evidenceDirectory = File(verifiedDirectory, "../verified"),
            )

            val rootLink = File(fixtureDirectory, "verified-root-link").toPath()
            val symlinkCreationFailure =
                runCatching { Files.createSymbolicLink(rootLink, verifiedDirectory.toPath()) }
                    .exceptionOrNull()
            if (symlinkCreationFailure != null) {
                val failure = symlinkCreationFailure
                val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
                val isWindows = operatingSystem.contains("windows")
                val skippable =
                    failure is UnsupportedOperationException ||
                        (isWindows &&
                            (failure is java.nio.file.FileSystemException ||
                                failure is SecurityException))
                if (!skippable || operatingSystem.contains("linux")) {
                    throw GradleException(
                        "Central verification symlink negative self-test could not run",
                        failure,
                    )
                }
                logger.lifecycle(
                    "Central verification symlink negative self-test skipped: " +
                        "${failure.javaClass.simpleName}: ${failure.message}"
                )
            } else {
                expectRejected(
                    "symlink evidence root",
                    normal,
                    evidenceDirectory = rootLink.toFile(),
                )

                val fileLinkDirectory =
                    File(fixtureDirectory, "verified-file-link").apply { mkdirs() }
                val evidenceFiles =
                    verifiedDirectory.listFiles()
                        ?: throw GradleException(
                            "Cannot enumerate Central verification evidence fixture"
                        )
                evidenceFiles.forEach { source ->
                    Files.copy(source.toPath(), fileLinkDirectory.toPath().resolve(source.name))
                }
                val linkedArtifact = fileLinkDirectory.toPath().resolve(evidenceHashes.keys.first())
                Files.delete(linkedArtifact)
                Files.createSymbolicLink(
                    linkedArtifact,
                    verifiedDirectory.toPath().resolve(linkedArtifact.fileName),
                )
                expectRejected(
                    "symlink evidence file",
                    normal,
                    evidenceDirectory = fileLinkDirectory,
                )
            }
        }
    }

val releaseShellScriptNames =
    listOf(
        "bootstrap_consumer_verification.sh",
        "build_release_docs.sh",
        "download_staging_evidence.sh",
        "download_verified_central_bytes.sh",
        "merge_consumer_verification.sh",
        "record_build_provenance.sh",
        "record_consumer_proof.sh",
        "run_strict_consumers.sh",
        "seal_verified_central_bytes.sh",
        "sign_release_handoff.sh",
        "stage_central_bundle.sh",
        "trusted_context.sh",
        "verify_release_commit_provenance.sh",
        "verify_target_checkout.sh",
        "wait_for_central_publication.sh",
    )
val releaseShellScripts =
    files(
        releaseShellScriptNames.map { name ->
            layout.projectDirectory.file("scripts/release/$name")
        }
    )

val releaseShellScriptSyntaxCheck =
    tasks.register<Exec>("releaseShellScriptSyntaxCheck") {
        description = "Checks the syntax of fixed repository scripts used by release workflows."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        workingDir(rootDir)
        inputs.files(releaseShellScripts)
        doFirst {
            val missing = releaseShellScripts.files.filterNot(File::isFile)
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Required release scripts are missing: ${missing.joinToString { it.name }}"
                )
            }
            commandLine(
                listOf("bash", "-n") +
                    releaseShellScripts.files.sortedBy { it.name }.map { it.absolutePath }
            )
        }
        outputs.upToDateWhen { false }
    }

val releaseWorkflowStaticCheck =
    tasks.register<JavaExec>("releaseWorkflowStaticCheck") {
        description =
            "Validates parsed YAML structure and static release wiring for release workflows."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(
            releaseShellScriptSyntaxCheck,
            releaseWorkflowStaticTest,
            tasks.named(sourceSets["releaseCheck"].classesTaskName),
        )

        val ciWorkflow = layout.projectDirectory.file(".github/workflows/ci.yml")
        val stageWorkflow =
            layout.projectDirectory.file(".github/workflows/publish-maven-central.yml")
        val docsWorkflow = layout.projectDirectory.file(".github/workflows/docs-deploy.yml")
        inputs.files(ciWorkflow, stageWorkflow, docsWorkflow)
        classpath = sourceSets["releaseCheck"].runtimeClasspath
        mainClass.set("io.github.ulviar.procwright.build.release.ReleaseWorkflowCheck")
        doFirst {
            setArgs(
                listOf(
                    ciWorkflow.asFile.absolutePath,
                    stageWorkflow.asFile.absolutePath,
                    docsWorkflow.asFile.absolutePath,
                )
            )
        }
    }

val publicPublicationProjectPaths = listOf(":", ":procwright-integrations", ":procwright-kotlin")

val cleanMavenCentralBundleRepository =
    tasks.register<Delete>("cleanMavenCentralBundleRepository") {
        description = "Removes the generated Maven Central bundle repository."
        group = LifecycleBasePlugin.BUILD_GROUP
        delete(mavenCentralBundleRepository)
    }

val mavenCentralSigningCheck =
    tasks.register("mavenCentralSigningCheck") {
        description = "Verifies that Maven Central signing material is available."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            val missing =
                listOf("SIGNING_KEY", "SIGNING_PASSWORD").filter { name ->
                    providers.environmentVariable(name).orNull.isNullOrBlank()
                }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Maven Central publication requires signing material in environment variables: ${
                        missing.joinToString()
                    }"
                )
            }
        }
    }

val publishMavenCentralBundleRepository =
    tasks.register("publishMavenCentralBundleRepository") {
        description = "Publishes all public artifacts into a local Maven Central bundle repository."
        group = PublishingPlugin.PUBLISH_TASK_GROUP
        dependsOn(cleanMavenCentralBundleRepository, mavenCentralSigningCheck)
        publicPublicationProjectPaths.forEach { projectPath ->
            val publishTask =
                if (projectPath == ":") {
                    "publishMavenJavaPublicationToMavenCentralBundleRepository"
                } else {
                    "$projectPath:publishMavenJavaPublicationToMavenCentralBundleRepository"
                }
            dependsOn(publishTask)
        }
    }

gradle.projectsEvaluated {
    publicPublicationProjectPaths.forEach { projectPath ->
        val checkedProject = project(projectPath)
        checkedProject.tasks
            .matching { task ->
                task.name == "publishMavenJavaPublicationToMavenCentralBundleRepository"
            }
            .configureEach { mustRunAfter(cleanMavenCentralBundleRepository) }
    }
}

val writeMavenCentralBundleChecksums =
    tasks.register("writeMavenCentralBundleChecksums") {
        description = "Writes Maven Central checksums and checks detached-signature coverage."
        group = LifecycleBasePlugin.BUILD_GROUP
        dependsOn(publishMavenCentralBundleRepository)

        val repository = mavenCentralBundleRepository
        inputs.dir(repository)
        outputs.dir(repository)

        doLast {
            val repositoryDir = repository.get().asFile
            val deployedFiles =
                repositoryDir
                    .walkTopDown()
                    .filter { file -> file.isFile }
                    .filterNot { file -> isMavenCentralChecksum(file) }
                    .filterNot { file -> file.name.endsWith(".asc") }
                    .filterNot { file -> file.name.startsWith("maven-metadata.xml") }
                    .toList()

            if (deployedFiles.isEmpty()) {
                throw GradleException("Maven Central bundle repository is empty: $repositoryDir")
            }

            deployedFiles.forEach { file ->
                val signature = File(file.parentFile, "${file.name}.asc")
                if (!signature.isFile) {
                    throw GradleException(
                        "Maven Central bundle file is missing detached signature: ${
                            file.relativeTo(repositoryDir)
                        }"
                    )
                }
                writeChecksum(file, "MD5", "md5")
                writeChecksum(file, "SHA-1", "sha1")
                writeChecksum(file, "SHA-256", "sha256")
                writeChecksum(file, "SHA-512", "sha512")
            }
        }
    }

val mavenCentralBundle =
    tasks.register<Zip>("mavenCentralBundle") {
        description = "Builds the signed Maven Central upload bundle."
        group = PublishingPlugin.PUBLISH_TASK_GROUP
        dependsOn(writeMavenCentralBundleChecksums)
        archiveFileName.set("procwright-$procwrightVersion-maven-central-bundle.zip")
        destinationDirectory.set(layout.buildDirectory.dir("maven-central"))
        from(mavenCentralBundleRepository) { exclude("**/maven-metadata.xml*") }
        inputs.files(
            layout.projectDirectory.file("scripts/release_contract.py"),
            layout.projectDirectory.file("scripts/maven_release_evidence.py"),
            layout.projectDirectory.file("scripts/verify_local_maven_central_bundle.py"),
        )
        doLast {
            val output = ByteArrayOutputStream()
            val processBuilder =
                ProcessBuilder(
                        "python3",
                        rootDir
                            .resolve("scripts/verify_local_maven_central_bundle.py")
                            .absolutePath,
                        "--repository",
                        mavenCentralBundleRepository.get().asFile.absolutePath,
                        "--bundle",
                        archiveFile.get().asFile.absolutePath,
                        "--version",
                        procwrightVersion,
                    )
                    .directory(rootDir)
                    .redirectErrorStream(true)
            processBuilder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            val process = processBuilder.start()
            process.inputStream.use { input -> input.copyTo(output) }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException(
                    "Maven Central bundle postcondition failed:\n${output.toString(Charsets.UTF_8)}"
                )
            }
        }
    }

fun isMavenCentralChecksum(file: File): Boolean =
    listOf(".md5", ".sha1", ".sha256", ".sha512").any { suffix -> file.name.endsWith(suffix) }

fun writeChecksum(file: File, algorithm: String, extension: String) {
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    File(file.parentFile, "${file.name}.$extension").writeText(digest.digest().toHexString())
}

fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

val releaseEvidenceScriptSelfTest =
    tasks.register<Exec>("releaseEvidenceScriptSelfTest") {
        description =
            "Runs bounded unit tests for staged-bundle and persistent Central release evidence."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        workingDir(rootDir)
        environment("PYTHONDONTWRITEBYTECODE", "1")
        commandLine(
            "python3",
            "-m",
            "unittest",
            "scripts/test_release_contract.py",
            "scripts/test_release_bootstrap_scripts.py",
            "scripts/test_release_handoff.py",
            "scripts/test_sign_release_handoff.py",
            "scripts/test_staging_release_artifact.py",
            "scripts/test_central_release_artifact.py",
            "scripts/test_verify_release_ci_run.py",
            "scripts/test_verify_release_handoff_artifact.py",
            "scripts/test_verify_docs_release_identity.py",
            "scripts/test_verify_pages_artifact.py",
            "scripts/test_maven_central_portal.py",
            "scripts/test_verify_maven_central_staged_bundle.py",
            "scripts/test_verify_maven_central_release.py",
            "scripts/test_verify_staging_evidence.py",
        )
        inputs.files(
            layout.projectDirectory.file("scripts/release_contract.py"),
            layout.projectDirectory.file("scripts/test_release_contract.py"),
            layout.projectDirectory.file("scripts/test_release_bootstrap_scripts.py"),
            layout.projectDirectory.file("scripts/release_handoff.py"),
            layout.projectDirectory.file("scripts/test_release_handoff.py"),
            layout.projectDirectory.file("scripts/test_sign_release_handoff.py"),
            layout.projectDirectory.file("scripts/staging_release_artifact.py"),
            layout.projectDirectory.file("scripts/test_staging_release_artifact.py"),
            layout.projectDirectory.file("scripts/central_release_artifact.py"),
            layout.projectDirectory.file("scripts/test_central_release_artifact.py"),
            layout.projectDirectory.file("scripts/verify_release_ci_run.py"),
            layout.projectDirectory.file("scripts/test_verify_release_ci_run.py"),
            layout.projectDirectory.file("scripts/verify_release_handoff_artifact.py"),
            layout.projectDirectory.file("scripts/test_verify_release_handoff_artifact.py"),
            layout.projectDirectory.file("scripts/verify_docs_release_identity.py"),
            layout.projectDirectory.file("scripts/test_verify_docs_release_identity.py"),
            layout.projectDirectory.file("scripts/verify_pages_artifact.py"),
            layout.projectDirectory.file("scripts/test_verify_pages_artifact.py"),
            layout.projectDirectory.file("scripts/maven_central_portal.py"),
            layout.projectDirectory.file("scripts/maven_central_repository.py"),
            layout.projectDirectory.file("scripts/maven_release_evidence.py"),
            layout.projectDirectory.file("scripts/maven_release_test_fixtures.py"),
            layout.projectDirectory.file("scripts/openpgp_verification.py"),
            layout.projectDirectory.file("scripts/test_maven_central_portal.py"),
            layout.projectDirectory.file("scripts/verify_maven_central_staged_bundle.py"),
            layout.projectDirectory.file("scripts/test_verify_maven_central_staged_bundle.py"),
            layout.projectDirectory.file("scripts/verify_maven_central_release.py"),
            layout.projectDirectory.file("scripts/test_verify_maven_central_release.py"),
            layout.projectDirectory.file("scripts/verify_staging_evidence.py"),
            layout.projectDirectory.file("scripts/test_verify_staging_evidence.py"),
            releaseShellScripts,
        )
        outputs.upToDateWhen { false }
    }

val mavenCentralBundlePostconditionSelfTest =
    tasks.register<Exec>("mavenCentralBundlePostconditionSelfTest") {
        description =
            "Proves that the production local-bundle verifier rejects extra classifiers and byte drift."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        workingDir(rootDir)
        environment("PYTHONDONTWRITEBYTECODE", "1")
        commandLine(
            "python3",
            "-m",
            "unittest",
            "scripts/test_verify_local_maven_central_bundle.py",
        )
        inputs.files(
            layout.projectDirectory.file("scripts/release_contract.py"),
            layout.projectDirectory.file("scripts/maven_release_evidence.py"),
            layout.projectDirectory.file("scripts/maven_release_test_fixtures.py"),
            layout.projectDirectory.file("scripts/verify_local_maven_central_bundle.py"),
            layout.projectDirectory.file("scripts/test_verify_local_maven_central_bundle.py"),
        )
        outputs.upToDateWhen { false }
    }

releaseEvidenceScriptSelfTest.configure { dependsOn(mavenCentralBundlePostconditionSelfTest) }

val realReleaseArtifactSemanticTest =
    tasks.register<Exec>("realReleaseArtifactSemanticTest") {
        description =
            "Publishes all real modules and proves the complete unsigned-handoff/GPG/staged-bundle roundtrip."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(cleanMavenCentralBundleRepository)
        publicPublicationProjectPaths.forEach { projectPath ->
            dependsOn(
                if (projectPath == ":") {
                    "publishMavenJavaPublicationToMavenCentralBundleRepository"
                } else {
                    "$projectPath:publishMavenJavaPublicationToMavenCentralBundleRepository"
                }
            )
        }
        workingDir(rootDir)
        environment("PYTHONDONTWRITEBYTECODE", "1")
        commandLine(
            "python3",
            "scripts/test_real_local_release.py",
            "--repository",
            mavenCentralBundleRepository.get().asFile.absolutePath,
            "--version",
            procwrightVersion,
        )
        inputs.files(
            layout.projectDirectory.file("scripts/release_contract.py"),
            layout.projectDirectory.file("scripts/maven_release_evidence.py"),
            layout.projectDirectory.file("scripts/maven_release_test_fixtures.py"),
            layout.projectDirectory.file("scripts/release_handoff.py"),
            layout.projectDirectory.file("scripts/verify_local_maven_central_bundle.py"),
            layout.projectDirectory.file("scripts/verify_maven_central_staged_bundle.py"),
            layout.projectDirectory.file("scripts/test_real_local_release.py"),
            releaseShellScripts,
        )
        outputs.upToDateWhen { false }
    }

tasks.check {
    dependsOn(
        releaseDocsContentSelfTest,
        centralConsumerVerificationMetadataSelfTest,
        releaseWorkflowStaticCheck,
    )
}

quickCheck.configure {
    dependsOn(
        releaseDocsContentSelfTest,
        centralConsumerVerificationMetadataSelfTest,
        releaseWorkflowStaticCheck,
    )
}

val releaseCandidateDependencies =
    mutableListOf<Provider<out Task>>(
        tasks.named("spotlessCheck"),
        regressionCheck,
        publicJavaJavadocCheck,
        publicDocsCheck,
        releaseWorkflowStaticCheck,
        releaseEvidenceScriptSelfTest,
        providers.provider { tasks.getByPath(":procwright-kotlin:check") },
        providers.provider { tasks.getByPath(":procwright-integrations:check") },
        providers.provider { tasks.getByPath(":procwright-test-cli:check") },
    )

releaseCandidateDependencies +=
    consumerExampleCheckPaths.map { path -> providers.provider { tasks.getByPath(path) } }

if (releaseCandidateFinalMode) {
    releaseCandidateDependencies += releaseDocsContentCheck
    releaseCandidateDependencies += realReleaseArtifactSemanticTest
}

val cleanWorkingTreeCheck =
    tasks.register("cleanWorkingTreeCheck") {
        description = "Verifies the Git worktree is clean, including untracked files."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        mustRunAfter(releaseCandidateDependencies)

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

val releaseCandidateCheck =
    tasks.register("releaseCandidateCheck") {
        description = "Runs the complete local release verification gate."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(releaseCandidateDependencies, cleanWorkingTreeCheck)
    }

val releaseCandidateModeSelfTest =
    tasks.register("releaseCandidateModeSelfTest") {
        description = "Proves SNAPSHOT and final-release task selection."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            if (
                releaseCandidateUsesFinalReleaseChecks("1.2.3-SNAPSHOT") ||
                    !releaseCandidateUsesFinalReleaseChecks("1.2.3")
            ) {
                throw GradleException("Release-candidate mode classification is inconsistent")
            }

            val releaseOnlyPaths =
                setOf(":releaseDocsContentCheck", ":realReleaseArtifactSemanticTest")
            val expectedReleaseOnlyPaths =
                if (releaseCandidateFinalMode) releaseOnlyPaths else emptySet()
            val expectedCandidatePaths =
                mutableSetOf(
                    ":spotlessCheck",
                    ":regressionCheck",
                    ":publicJavaJavadocCheck",
                    ":publicDocsCheck",
                    ":releaseWorkflowStaticCheck",
                    ":releaseEvidenceScriptSelfTest",
                    ":procwright-kotlin:check",
                    ":procwright-integrations:check",
                    ":procwright-test-cli:check",
                    *consumerExampleCheckPaths.toTypedArray(),
                )
            expectedCandidatePaths += expectedReleaseOnlyPaths

            fun dependencyPaths(task: Task, dependency: TaskDependency): Set<String> =
                dependency.getDependencies(task).map(Task::getPath).toSet()

            fun assertPaths(owner: String, actual: Set<String>, expected: Set<String>) {
                if (actual != expected) {
                    throw GradleException(
                        "$owner wiring differs for version $procwrightVersion; " +
                            "missing=${(expected - actual).sorted()}, " +
                            "unexpected=${(actual - expected).sorted()}"
                    )
                }
            }

            val candidate = releaseCandidateCheck.get()
            assertPaths(
                "releaseCandidateCheck",
                dependencyPaths(candidate, candidate.taskDependencies),
                expectedCandidatePaths + cleanWorkingTreeCheck.get().path,
            )

            val docs = publicDocsCheck.get()
            val expectedDocsPaths =
                setOf(
                    ":preparePublicDocs",
                    ":docsRequirementsLockCheck",
                    ":canonicalPublicDocsUrlCheck",
                ) + expectedReleaseOnlyPaths.intersect(setOf(":releaseDocsContentCheck"))
            assertPaths(
                "publicDocsCheck",
                dependencyPaths(docs, docs.taskDependencies),
                expectedDocsPaths,
            )

            val clean = cleanWorkingTreeCheck.get()
            assertPaths(
                "cleanWorkingTreeCheck.mustRunAfter",
                dependencyPaths(clean, clean.mustRunAfter),
                expectedCandidatePaths,
            )
        }
    }

tasks.check { dependsOn(releaseCandidateModeSelfTest) }

quickCheck.configure { dependsOn(releaseCandidateModeSelfTest) }

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
