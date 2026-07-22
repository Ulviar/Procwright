import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
    signing
}

val procwrightJavaRelease = rootProject.extra["procwrightJavaRelease"] as Int
val procwrightJavaVersion = rootProject.extra["procwrightJavaVersion"] as JavaVersion
val kotlinNullnessCompiler by configurations.creating

dependencies {
    api(project(":"))
    api("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    compileOnlyApi("org.jspecify:jspecify:1.0.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.jspecify:jspecify:1.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    kotlinNullnessCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21")
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
            artifactId = "procwright-integrations"
            pom {
                name.set("Procwright Integrations")
                description.set("Optional protocol and tool-adapter helpers for Procwright.")
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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(procwrightJavaRelease)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

data class KotlinNullnessFixture(
    val name: String,
    val source: String,
    val expectedSourceSha256: String,
    val expectedSuccess: Boolean,
    val expectedDiagnostic: KotlinNullnessDiagnostic? = null,
)

data class KotlinNullnessDiagnostic(
    val line: Int,
    val column: Int,
    val sourceLine: String,
    val fragment: String,
)

val unrelatedCompilerFailureDiagnostics =
    listOf("unresolved reference", "syntax error", "expecting an element")

fun assertKotlinNullnessFixtureResult(
    fixture: KotlinNullnessFixture,
    sourceText: String,
    exitValue: Int,
    output: String,
) {
    val canonicalSource = canonicalKotlinSource(sourceText)
    val actualSourceSha256 = sha256(canonicalSource)
    if (actualSourceSha256 != fixture.expectedSourceSha256) {
        throw GradleException(
            "Kotlin integration nullness fixture ${fixture.name} source identity changed: expected ${fixture.expectedSourceSha256}, got $actualSourceSha256"
        )
    }
    if (fixture.expectedSuccess && exitValue != 0) {
        throw GradleException("Positive Kotlin integration nullness fixture failed:\n$output")
    }
    if (!fixture.expectedSuccess && exitValue == 0) {
        throw GradleException(
            "Negative Kotlin integration nullness fixture ${fixture.name} compiled successfully"
        )
    }
    if (!fixture.expectedSuccess) {
        val expected = fixture.expectedDiagnostic
        if (expected == null) {
            throw GradleException(
                "Negative Kotlin integration nullness fixture ${fixture.name} declares no expected diagnostic"
            )
        }
        val sourceLines = canonicalSource.lines()
        val actualSourceLine = sourceLines.getOrNull(expected.line - 1)
        if (actualSourceLine != expected.sourceLine) {
            throw GradleException(
                "Kotlin integration nullness fixture ${fixture.name} expected source expression '${expected.sourceLine}' at ${fixture.source}:${expected.line}, got '${actualSourceLine.orEmpty()}'"
            )
        }
        unrelatedCompilerFailureDiagnostics.forEach { unrelated ->
            if (output.contains(unrelated, ignoreCase = true)) {
                throw GradleException(
                    "Kotlin integration nullness fixture ${fixture.name} failed for unrelated compiler diagnostic '$unrelated':\n$output"
                )
            }
        }
        val normalizedOutput = output.replace('\\', '/')
        val sourcePath = fixture.source.replace('\\', '/')
        val location = "$sourcePath:${expected.line}:${expected.column}: error: "
        val diagnosticLine =
            normalizedOutput.lineSequence().firstOrNull { line ->
                val locationIndex = line.indexOf(location)
                locationIndex >= 0 && (locationIndex == 0 || line[locationIndex - 1] == '/')
            }
        if (diagnosticLine == null) {
            throw GradleException(
                "Kotlin integration nullness fixture ${fixture.name} did not report its expected diagnostic at $sourcePath:${expected.line}:${expected.column}:\n$output"
            )
        }
        if (!diagnosticLine.substringAfter(location).contains(expected.fragment)) {
            throw GradleException(
                "Kotlin integration nullness fixture ${fixture.name} diagnostic at $sourcePath:${expected.line}:${expected.column} did not contain exact fragment '${expected.fragment}':\n$output"
            )
        }
    }
}

fun canonicalKotlinSource(source: String): String = source.replace("\r\n", "\n").replace('\r', '\n')

fun sha256(source: String): String =
    MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString(
        ""
    ) { byte ->
        "%02x".format(byte)
    }

val kotlinNullnessFixtureClasspath =
    sourceSets.main.get().output +
        configurations.compileClasspath.get() +
        kotlinNullnessCompiler.filter { it.name.startsWith("kotlin-stdlib-") }
val kotlinNullnessFixtures =
    listOf(
        KotlinNullnessFixture(
            "integrationPositive",
            "src/nullnessFixtures/kotlin/positive/ValidIntegrationNullness.kt",
            "983a2bb1a03add43c60c8d0d8b311c457579e630e67ff646009a789f67de4000",
            true,
        ),
        KotlinNullnessFixture(
            "integrationParameter",
            "src/nullnessFixtures/kotlin/negative/NullableIntegrationParameter.kt",
            "6ca498e7c4e079798690ec0cf765bd0125e5ad00742496fc9605c27efdc882a1",
            false,
            KotlinNullnessDiagnostic(
                8,
                15,
                "    tool.call(null)",
                "null cannot be a value of a non-null type 'String'",
            ),
        ),
        KotlinNullnessFixture(
            "integrationReturn",
            "src/nullnessFixtures/kotlin/negative/NullableIntegrationReturn.kt",
            "0dac90ef004659fc6aaf1da34140b5b4676521350c07d32636766f40a0207fab",
            false,
            KotlinNullnessDiagnostic(
                8,
                41,
                "    override fun handle(input: String): String? = null",
                "return type of 'fun handle(input: String): String?' is not a subtype of the return type of the overridden member 'fun handle(p0: String): String'",
            ),
        ),
        KotlinNullnessFixture(
            "integrationGenericBound",
            "src/nullnessFixtures/kotlin/negative/NullableIntegrationGenericBound.kt",
            "e29466010495495b6291dd82bdd2b05792a7272d807414818271169ad39f7063",
            false,
            KotlinNullnessDiagnostic(
                8,
                29,
                "    tool: CommandBackedTool<String?, String?>",
                "type argument is not within its bounds",
            ),
        ),
        KotlinNullnessFixture(
            "integrationNestedContainer",
            "src/nullnessFixtures/kotlin/negative/NullableIntegrationNestedContainer.kt",
            "725a1352faf009fccd0edd7a392fb82f54d3b90c79a50617a9424491f5921504",
            false,
            KotlinNullnessDiagnostic(
                8,
                24,
                "    JsonValue.`object`(values)",
                "argument type mismatch: actual type is 'Map<String, JsonValue?>', but '(Mutable)Map<String, JsonValue>' was expected",
            ),
        ),
    )

val kotlinNullnessFixtureTasks =
    kotlinNullnessFixtures.map { fixture ->
        tasks.register<JavaExec>(
            "compile${fixture.name.replaceFirstChar(Char::uppercase)}NullnessFixture"
        ) {
            description =
                "Compiles the ${fixture.name} fixture against integration APIs with strict JSpecify semantics."
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            dependsOn(tasks.named("classes"))

            val sourceFile = layout.projectDirectory.file(fixture.source)
            val outputDirectory = layout.buildDirectory.dir("nullness-fixtures/${fixture.name}")
            val diagnostics = ByteArrayOutputStream()
            inputs.file(sourceFile)
            inputs.files(kotlinNullnessFixtureClasspath)
            outputs.upToDateWhen { false }
            classpath = kotlinNullnessCompiler
            mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            isIgnoreExitValue = true
            standardOutput = diagnostics
            errorOutput = diagnostics
            argumentProviders.add(
                CommandLineArgumentProvider {
                    listOf(
                        "-no-stdlib",
                        "-no-reflect",
                        "-Xjspecify-annotations=strict",
                        "-classpath",
                        kotlinNullnessFixtureClasspath.filter(File::exists).asPath,
                        "-d",
                        outputDirectory.get().asFile.absolutePath,
                        sourceFile.asFile.absolutePath,
                    )
                }
            )
            doFirst {
                diagnostics.reset()
                delete(outputDirectory)
            }
            doLast {
                val sourceText = sourceFile.asFile.readText(Charsets.UTF_8)
                assertKotlinNullnessFixtureResult(
                    fixture,
                    sourceText,
                    executionResult.get().exitValue,
                    diagnostics.toString(Charsets.UTF_8),
                )
            }
        }
    }

val integrationKotlinNullnessHarnessSelfTest =
    tasks.register("integrationKotlinNullnessHarnessSelfTest") {
        description =
            "Proves missing, unrelated, and wrong compiler diagnostics cannot satisfy a negative nullness fixture."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        doLast {
            val source =
                "fun intended() {\n    api(null)\n}\n\nfun unrelated() {\n    api(null)\n}\n"
            val fixture =
                KotlinNullnessFixture(
                    "harnessSelfTest",
                    "harness.kt",
                    "0f7623d4fe675bb77302e035be287728fe9f05ce2fa04c18626f67293c0fa028",
                    false,
                    KotlinNullnessDiagnostic(
                        2,
                        5,
                        "    api(null)",
                        "null cannot be a value of a non-null type 'String'",
                    ),
                )

            fun assertRejected(
                output: String,
                expectedMessage: String,
                actualSource: String = source,
            ) {
                try {
                    assertKotlinNullnessFixtureResult(fixture, actualSource, 1, output)
                    throw GradleException(
                        "Wrong compiler diagnostics satisfied the nullness fixture harness"
                    )
                } catch (expected: GradleException) {
                    if (!expected.message.orEmpty().contains(expectedMessage)) {
                        throw expected
                    }
                }
            }

            val missingDiagnostics =
                fixture.copy(name = "missingDiagnostics", expectedDiagnostic = null)
            try {
                assertKotlinNullnessFixtureResult(missingDiagnostics, source, 1, "compiler error")
                throw GradleException(
                    "A negative fixture without expected diagnostics was accepted"
                )
            } catch (expected: GradleException) {
                if (!expected.message.orEmpty().contains("declares no expected diagnostic")) {
                    throw expected
                }
            }

            assertRejected(
                "harness.kt:2:5: error: null cannot be a value of a non-null type 'String'",
                "source identity changed",
                source.replace("fun intended", "fun changed"),
            )

            assertRejected(
                "harness.kt:2:5: error: unresolved reference: misspelledApi",
                "unrelated compiler diagnostic",
            )
            assertRejected(
                "harness.kt:2:5: error: argument type mismatch: actual type is 'String?', but 'String' was expected",
                "did not contain exact fragment",
            )
            assertRejected(
                "harness.kt:6:5: error: null cannot be a value of a non-null type 'String'",
                "did not report its expected diagnostic",
            )
        }
    }

val integrationKotlinNullnessCheck =
    tasks.register("integrationKotlinNullnessCheck") {
        description =
            "Compiles positive and negative Kotlin consumers of the integration nullness contract."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(kotlinNullnessFixtureTasks, integrationKotlinNullnessHarnessSelfTest)
    }

tasks.check { dependsOn(integrationKotlinNullnessCheck) }
