import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    `java-library`
    application
}

val procwrightJavaRelease = rootProject.extra["procwrightJavaRelease"] as Int
val procwrightJavaVersion = rootProject.extra["procwrightJavaVersion"] as JavaVersion
val jmhVersion = "1.37"
val comparisonNativeAccessArg = "--enable-native-access=ALL-UNNAMED"
val jmhUnsafeAccessArg = "--sun-misc-unsafe-memory-access=allow"
val comparisonReportsDirectory = layout.buildDirectory.dir("reports/comparison")
val applicationRunOutput =
    comparisonReportsDirectory.map { directory -> directory.file("application-results.md") }
val comparisonReportOutput =
    comparisonReportsDirectory.map { directory -> directory.file("results.md") }
val comparisonCheckOutput =
    comparisonReportsDirectory.map { directory -> directory.file("verification-results.md") }
val stressComparisonReportOutput =
    comparisonReportsDirectory.map { directory -> directory.file("stress-results.md") }
val jmhReportsDirectory = layout.buildDirectory.dir("reports/jmh")
val jmhBenchmarkResult = jmhReportsDirectory.map { directory -> directory.file("results.json") }
val jmhBenchmarkTemporaryResult =
    jmhReportsDirectory.map { directory -> directory.file("results.json.tmp") }
val jmhBenchmarkSmokeResult =
    jmhReportsDirectory.map { directory -> directory.file("smoke-results.json") }
val jmhBenchmarkSmokeTemporaryResult =
    jmhReportsDirectory.map { directory -> directory.file("smoke-results.json.tmp") }
val jmhPtyBenchmarkResult =
    jmhReportsDirectory.map { directory -> directory.file("pty-results.json") }
val jmhPtyBenchmarkTemporaryResult =
    jmhReportsDirectory.map { directory -> directory.file("pty-results.json.tmp") }
val jmhMemoryBenchmarkResult =
    jmhReportsDirectory.map { directory -> directory.file("memory-results.json") }
val jmhMemoryBenchmarkTemporaryResult =
    jmhReportsDirectory.map { directory -> directory.file("memory-results.json.tmp") }
val jmhMemoryBenchmarkSmokeResult =
    jmhReportsDirectory.map { directory -> directory.file("memory-smoke-results.json") }
val jmhMemoryBenchmarkSmokeTemporaryResult =
    jmhReportsDirectory.map { directory -> directory.file("memory-smoke-results.json.tmp") }
val jmhJvmArgs = buildList {
    add(comparisonNativeAccessArg)
    if (Runtime.version().feature() >= 24) {
        add(jmhUnsafeAccessArg)
    }
}

sourceSets {
    val jmh by creating {
        java.srcDir("src/jmh/java")
        resources.srcDir("src/jmh/resources")
        compileClasspath += sourceSets.main.get().output
        compileClasspath += configurations.runtimeClasspath.get()
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
    }
}

dependencies {
    implementation(project(":"))
    implementation(project(":procwright-integrations"))
    implementation(project(":procwright-test-cli"))
    implementation("org.apache.commons:commons-exec:1.6.0")
    implementation("org.zeroturnaround:zt-exec:1.12")
    implementation("com.zaxxer:nuprocess:3.0.0")
    implementation("org.jetbrains.pty4j:pty4j:0.13.12")
    implementation("net.sf.expectit:expectit-core:0.9.0")
    implementation("org.slf4j:slf4j-api:2.0.18")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "jmhImplementation"("org.openjdk.jmh:jmh-core:$jmhVersion")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

java {
    sourceCompatibility = procwrightJavaVersion
    targetCompatibility = procwrightJavaVersion
}

application { mainClass.set("io.github.ulviar.procwright.comparison.ComparisonRunner") }

val applicationRun =
    tasks.named<JavaExec>("run") {
        jvmArgs(comparisonNativeAccessArg)
        args(applicationRunOutput.get().asFile.absolutePath)
        outputs.file(applicationRunOutput)
    }

configurations.named("jmhImplementation") { extendsFrom(configurations.implementation.get()) }

configurations.named("jmhRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(procwrightJavaRelease)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

tasks.register("jmhCompileCheck") {
    description = "Compiles JMH benchmark sources and generated benchmark metadata."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("jmhClasses"))
}

val comparisonReport =
    tasks.register<JavaExec>("comparisonReport") {
        description = "Runs library comparison scenarios and writes a Markdown report."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(application.mainClass)
        jvmArgs(comparisonNativeAccessArg)
        args(comparisonReportOutput.get().asFile.absolutePath)
        outputs.file(comparisonReportOutput)
        systemProperties(
            System.getProperties()
                .entries
                .associate { it.key.toString() to it.value.toString() }
                .filterKeys { it.startsWith("procwright.comparison.") }
        )
    }

val comparisonCheck =
    tasks.register<JavaExec>("comparisonCheck") {
        description = "Runs comparison regression checks and writes isolated build evidence."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(application.mainClass)
        jvmArgs(comparisonNativeAccessArg)
        args("--verify", comparisonCheckOutput.get().asFile.absolutePath)
        outputs.file(comparisonCheckOutput)
        systemProperties(
            System.getProperties()
                .entries
                .associate { it.key.toString() to it.value.toString() }
                .filterKeys { it.startsWith("procwright.comparison.") }
        )
    }

val stressComparisonReport =
    tasks.register<JavaExec>("stressComparisonReport") {
        description =
            "Runs stress comparison scenarios on the reusable test CLI and writes a Markdown report."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("io.github.ulviar.procwright.comparison.StressComparisonRunner")
        jvmArgs(comparisonNativeAccessArg)
        args(stressComparisonReportOutput.get().asFile.absolutePath)
        outputs.file(stressComparisonReportOutput)
        systemProperties(
            System.getProperties()
                .entries
                .associate { it.key.toString() to it.value.toString() }
                .filterKeys { it.startsWith("procwright.comparison.") }
        )
    }

val jmhBenchmark =
    tasks.register<JavaExec>("jmhBenchmark") {
        description = "Runs the full non-PTY JMH benchmark suite for comparison scenarios."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named("jmhClasses"))
        classpath = sourceSets["jmh"].runtimeClasspath
        mainClass.set("org.openjdk.jmh.Main")
        jvmArgs(jmhJvmArgs)

        outputs.file(jmhBenchmarkResult)
        outputs.upToDateWhen { false }
        doFirst {
            prepareJmhResult(
                jmhBenchmarkTemporaryResult.get().asFile,
                jmhBenchmarkResult.get().asFile,
            )
        }
        doLast {
            promoteJmhResult(
                jmhBenchmarkTemporaryResult.get().asFile,
                jmhBenchmarkResult.get().asFile,
            )
            verifyPublishedJmhResult(
                path,
                jmhBenchmarkTemporaryResult.get().asFile,
                jmhBenchmarkResult.get().asFile,
                outputs.files.files,
            )
        }
        args(
            "-foe",
            "true",
            "-rf",
            "json",
            "-rff",
            jmhBenchmarkTemporaryResult.get().asFile.absolutePath,
            "io.github.ulviar.procwright.(comparison.(BulkArgument|OneShot|Streaming|LineSession|Expect|Pooled|ToolObservation|JsonFraming)|internal.session.(ExpectMatchBuffer|ProtocolTextFraming)).*",
        )
    }

val jmhBenchmarkSmoke =
    tasks.register<JavaExec>("jmhBenchmarkSmoke") {
        description = "Runs a short JMH smoke benchmark to verify benchmark wiring."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named("jmhClasses"))
        classpath = sourceSets["jmh"].runtimeClasspath
        mainClass.set("org.openjdk.jmh.Main")
        jvmArgs(jmhJvmArgs)

        outputs.file(jmhBenchmarkSmokeResult)
        outputs.upToDateWhen { false }
        doFirst {
            prepareJmhResult(
                jmhBenchmarkSmokeTemporaryResult.get().asFile,
                jmhBenchmarkSmokeResult.get().asFile,
            )
        }
        doLast {
            promoteJmhResult(
                jmhBenchmarkSmokeTemporaryResult.get().asFile,
                jmhBenchmarkSmokeResult.get().asFile,
            )
            verifyPublishedJmhResult(
                path,
                jmhBenchmarkSmokeTemporaryResult.get().asFile,
                jmhBenchmarkSmokeResult.get().asFile,
                outputs.files.files,
            )
        }
        args(
            "-foe",
            "true",
            "-wi",
            "1",
            "-i",
            "1",
            "-f",
            "1",
            "-w",
            "200ms",
            "-r",
            "200ms",
            "-rf",
            "json",
            "-rff",
            jmhBenchmarkSmokeTemporaryResult.get().asFile.absolutePath,
            "io.github.ulviar.procwright.comparison.(OneShotProcessBenchmark.success|BulkArgumentDraftBenchmark.(commandSpec|runDraft))",
        )
    }

val jmhPtyBenchmark =
    tasks.register<JavaExec>("jmhPtyBenchmark") {
        description = "Runs optional PTY JMH benchmarks on platforms with PTY capability."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named("jmhClasses"))
        classpath = sourceSets["jmh"].runtimeClasspath
        mainClass.set("org.openjdk.jmh.Main")
        jvmArgs(jmhJvmArgs)

        outputs.file(jmhPtyBenchmarkResult)
        outputs.upToDateWhen { false }
        doFirst {
            prepareJmhResult(
                jmhPtyBenchmarkTemporaryResult.get().asFile,
                jmhPtyBenchmarkResult.get().asFile,
            )
        }
        doLast {
            promoteJmhResult(
                jmhPtyBenchmarkTemporaryResult.get().asFile,
                jmhPtyBenchmarkResult.get().asFile,
            )
            verifyPublishedJmhResult(
                path,
                jmhPtyBenchmarkTemporaryResult.get().asFile,
                jmhPtyBenchmarkResult.get().asFile,
                outputs.files.files,
            )
        }
        args(
            "-foe",
            "true",
            "-rf",
            "json",
            "-rff",
            jmhPtyBenchmarkTemporaryResult.get().asFile.absolutePath,
            "io.github.ulviar.procwright.comparison.PtyProcessBenchmark",
        )
    }

val jmhMemoryBenchmark =
    tasks.register<JavaExec>("jmhMemoryBenchmark") {
        description =
            "Profiles allocation for representative non-PTY scenarios with the JMH GC profiler."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named("jmhClasses"))
        classpath = sourceSets["jmh"].runtimeClasspath
        mainClass.set("org.openjdk.jmh.Main")
        jvmArgs(jmhJvmArgs)

        outputs.file(jmhMemoryBenchmarkResult)
        outputs.upToDateWhen { false }
        doFirst {
            prepareJmhResult(
                jmhMemoryBenchmarkTemporaryResult.get().asFile,
                jmhMemoryBenchmarkResult.get().asFile,
            )
        }
        doLast {
            promoteJmhResult(
                jmhMemoryBenchmarkTemporaryResult.get().asFile,
                jmhMemoryBenchmarkResult.get().asFile,
            )
            verifyPublishedJmhResult(
                path,
                jmhMemoryBenchmarkTemporaryResult.get().asFile,
                jmhMemoryBenchmarkResult.get().asFile,
                outputs.files.files,
            )
        }
        args(
            "-foe",
            "true",
            "-prof",
            "gc",
            "-rf",
            "json",
            "-rff",
            jmhMemoryBenchmarkTemporaryResult.get().asFile.absolutePath,
            "io.github.ulviar.procwright.(comparison.(OneShotProcessBenchmark.(success|largeStdoutBoundedCapture)|StreamingProcessBenchmark.stdoutAndStderrCallbacks|LineSessionProcessBenchmark.twoRequests|JsonFramingBenchmark.*)|internal.(OneShotCaptureAllocationBenchmark|session.ProtocolTextFramingBenchmark.*))",
        )
    }

val jmhMemoryBenchmarkSmoke =
    tasks.register<JavaExec>("jmhMemoryBenchmarkSmoke") {
        description = "Runs a short allocation-profile smoke benchmark for Procwright."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.named("jmhClasses"))
        classpath = sourceSets["jmh"].runtimeClasspath
        mainClass.set("org.openjdk.jmh.Main")
        jvmArgs(jmhJvmArgs)

        outputs.file(jmhMemoryBenchmarkSmokeResult)
        outputs.upToDateWhen { false }
        doFirst {
            prepareJmhResult(
                jmhMemoryBenchmarkSmokeTemporaryResult.get().asFile,
                jmhMemoryBenchmarkSmokeResult.get().asFile,
            )
        }
        doLast {
            promoteJmhResult(
                jmhMemoryBenchmarkSmokeTemporaryResult.get().asFile,
                jmhMemoryBenchmarkSmokeResult.get().asFile,
            )
            verifyPublishedJmhResult(
                path,
                jmhMemoryBenchmarkSmokeTemporaryResult.get().asFile,
                jmhMemoryBenchmarkSmokeResult.get().asFile,
                outputs.files.files,
            )
        }
        args(
            "-foe",
            "true",
            "-prof",
            "gc",
            "-p",
            "inputBytes=20000",
            "-wi",
            "1",
            "-i",
            "1",
            "-f",
            "1",
            "-w",
            "200ms",
            "-r",
            "200ms",
            "-rf",
            "json",
            "-rff",
            jmhMemoryBenchmarkSmokeTemporaryResult.get().asFile.absolutePath,
            "io.github.ulviar.procwright.internal.OneShotCaptureAllocationBenchmark",
        )
    }

tasks.register("comparisonReportDefaultsCheck") {
    description = "Verifies every comparison and JMH writer has one unique declared build output."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("test"))

    doLast {
        val markdownWriters =
            listOf(
                Triple(applicationRun, applicationRunOutput, false),
                Triple(comparisonReport, comparisonReportOutput, false),
                Triple(comparisonCheck, comparisonCheckOutput, true),
                Triple(stressComparisonReport, stressComparisonReportOutput, false),
            )
        val expectedMarkdownTasks =
            setOf(
                ":procwright-comparison:run",
                ":procwright-comparison:comparisonReport",
                ":procwright-comparison:comparisonCheck",
                ":procwright-comparison:stressComparisonReport",
            )
        val actualMarkdownTasks = markdownWriters.map { it.first.get().path }.toSet()
        if (actualMarkdownTasks != expectedMarkdownTasks) {
            throw GradleException(
                "Markdown writer guard must enumerate exactly $expectedMarkdownTasks, got $actualMarkdownTasks"
            )
        }
        val markdownOutputs =
            markdownWriters.map { (taskProvider, output, _) ->
                taskProvider.get().path to output.get().asFile
            }
        requireUniqueCanonicalWriterOutputs("Markdown", 4, markdownOutputs)

        val markdownDirectory = comparisonReportsDirectory.get().asFile.canonicalFile
        markdownWriters.forEach { (taskProvider, output, verify) ->
            val outputFile = output.get().asFile.canonicalFile
            if (outputFile.parentFile != markdownDirectory) {
                throw GradleException(
                    "Comparison report default must be under $markdownDirectory: $outputFile"
                )
            }
            val reportTask = taskProvider.get()
            val expectedArguments =
                if (verify) listOf("--verify", outputFile.absolutePath)
                else listOf(outputFile.absolutePath)
            if (reportTask.args != expectedArguments) {
                throw GradleException(
                    "${reportTask.path} must pass only its declared report output: ${reportTask.args}"
                )
            }
            requireOnlyDeclaredOutput(reportTask.path, reportTask.outputs.files.files, outputFile)
        }

        val jmhWriters =
            listOf(
                jmhBenchmark to jmhBenchmarkResult,
                jmhBenchmarkSmoke to jmhBenchmarkSmokeResult,
                jmhPtyBenchmark to jmhPtyBenchmarkResult,
                jmhMemoryBenchmark to jmhMemoryBenchmarkResult,
                jmhMemoryBenchmarkSmoke to jmhMemoryBenchmarkSmokeResult,
            )
        val expectedJmhTasks =
            setOf(
                ":procwright-comparison:jmhBenchmark",
                ":procwright-comparison:jmhBenchmarkSmoke",
                ":procwright-comparison:jmhPtyBenchmark",
                ":procwright-comparison:jmhMemoryBenchmark",
                ":procwright-comparison:jmhMemoryBenchmarkSmoke",
            )
        val actualJmhTasks = jmhWriters.map { it.first.get().path }.toSet()
        if (actualJmhTasks != expectedJmhTasks) {
            throw GradleException(
                "JMH writer guard must enumerate exactly $expectedJmhTasks, got $actualJmhTasks"
            )
        }
        val jmhOutputs =
            jmhWriters.map { (taskProvider, output) ->
                taskProvider.get().path to output.get().asFile
            }
        requireUniqueCanonicalWriterOutputs("JMH", 5, jmhOutputs)
        requireUniqueCanonicalWriterOutputs("all comparison", 9, markdownOutputs + jmhOutputs)

        val jmhDirectory = jmhReportsDirectory.get().asFile.canonicalFile
        jmhWriters.forEach { (taskProvider, output) ->
            val outputFile = output.get().asFile.canonicalFile
            if (outputFile.parentFile != jmhDirectory) {
                throw GradleException("JMH result must be under $jmhDirectory: $outputFile")
            }
            val writerTask = taskProvider.get()
            requireOnlyDeclaredOutput(writerTask.path, writerTask.outputs.files.files, outputFile)
        }

        val duplicatePath = File(temporaryDir, "duplicate-output.json")
        val duplicateFailure =
            runCatching {
                    requireUniqueCanonicalWriterOutputs(
                        "negative fixture",
                        2,
                        listOf(
                            "fixture:first" to duplicatePath,
                            "fixture:second" to
                                File(duplicatePath.parentFile, "./${duplicatePath.name}"),
                        ),
                    )
                }
                .exceptionOrNull()
        if (
            duplicateFailure !is GradleException ||
                !duplicateFailure.message.orEmpty().contains("duplicate canonical output")
        ) {
            throw GradleException(
                "Writer output guard did not reject duplicate canonical paths",
                duplicateFailure,
            )
        }
    }
}

tasks.named("check") {
    description =
        "No-op for the research comparison module; run comparisonCheck or JMH tasks explicitly."
    setDependsOn(emptyList<Any>())
}

tasks.named<Javadoc>("javadoc") {
    enabled = false
    description = "Disabled because this project is a research harness, not a published API."
}

fun prepareJmhResult(temporary: File, published: File) {
    temporary.parentFile.mkdirs()
    temporary.delete()
    published.delete()
}

fun promoteJmhResult(temporary: File, published: File) {
    if (
        !temporary.isFile ||
            temporary.length() == 0L ||
            !temporary.readText().contains("\"benchmark\"")
    ) {
        throw GradleException("JMH did not produce a non-empty benchmark result: $temporary")
    }
    Files.move(temporary.toPath(), published.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

fun verifyPublishedJmhResult(
    taskPath: String,
    temporary: File,
    published: File,
    declaredOutputs: Set<File>,
) {
    val json = published.takeIf(File::isFile)?.readText()?.trim().orEmpty()
    if (
        json.isEmpty() ||
            !json.startsWith("[") ||
            !json.endsWith("]") ||
            !json.contains("\"benchmark\"")
    ) {
        throw GradleException("$taskPath did not publish a valid final JMH JSON file: $published")
    }
    if (temporary.exists()) {
        throw GradleException("$taskPath left a temporary JMH result behind: $temporary")
    }
    requireOnlyDeclaredOutput(taskPath, declaredOutputs, published)
}

fun requireOnlyDeclaredOutput(taskPath: String, declaredOutputs: Set<File>, expected: File) {
    val canonicalOutputs = declaredOutputs.map(File::getCanonicalFile).toSet()
    val canonicalExpected = expected.canonicalFile
    if (canonicalOutputs != setOf(canonicalExpected)) {
        throw GradleException(
            "$taskPath must declare only its final output $canonicalExpected, got $canonicalOutputs"
        )
    }
}

fun requireUniqueCanonicalWriterOutputs(
    label: String,
    expectedCount: Int,
    writers: List<Pair<String, File>>,
) {
    if (writers.size != expectedCount) {
        throw GradleException(
            "$label writer guard expected $expectedCount entries, got ${writers.size}"
        )
    }
    val writerNames = writers.map { it.first }
    if (writerNames.toSet().size != expectedCount) {
        throw GradleException("$label writer guard contains duplicate task names: $writerNames")
    }
    val canonicalWriters = writers.map { (writer, output) -> writer to output.canonicalFile }
    val duplicates =
        canonicalWriters
            .groupBy(keySelector = { it.second }, valueTransform = { it.first })
            .filterValues { names -> names.size > 1 }
    if (duplicates.isNotEmpty()) {
        throw GradleException("$label writers have duplicate canonical output paths: $duplicates")
    }
}
