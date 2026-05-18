plugins {
    `java-library`
    application
}

val jmhVersion = "1.37"
val comparisonNativeAccessArg = "--enable-native-access=ALL-UNNAMED"
val jmhUnsafeAccessArg = "--sun-misc-unsafe-memory-access=allow"

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
    implementation(project(":icli-integrations"))
    implementation(project(":icli-test-cli"))
    implementation("org.apache.commons:commons-exec:1.6.0")
    implementation("org.zeroturnaround:zt-exec:1.12")
    implementation("com.zaxxer:nuprocess:3.0.0")
    implementation("org.jetbrains.pty4j:pty4j:0.13.12")
    implementation("net.sf.expectit:expectit-core:0.9.0")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "jmhImplementation"("org.openjdk.jmh:jmh-core:$jmhVersion")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

application { mainClass.set("com.github.ulviar.icli.comparison.ComparisonRunner") }

configurations.named("jmhImplementation") { extendsFrom(configurations.implementation.get()) }

configurations.named("jmhRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

tasks.register("jmhCompileCheck") {
    description = "Compiles JMH benchmark sources and generated benchmark metadata."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("jmhClasses"))
}

tasks.register<JavaExec>("comparisonReport") {
    description = "Runs library comparison scenarios and writes a Markdown report."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    jvmArgs(comparisonNativeAccessArg)
    args(
        layout.projectDirectory.dir("../context/comparison").file("results.md").asFile.absolutePath
    )
    systemProperties(
        System.getProperties()
            .entries
            .associate { it.key.toString() to it.value.toString() }
            .filterKeys { it.startsWith("icli.comparison.") }
    )
}

tasks.register<JavaExec>("comparisonCheck") {
    description = "Runs non-mutating comparison regression checks."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    jvmArgs(comparisonNativeAccessArg)
    args(
        "--verify",
        layout.buildDirectory.file("reports/comparison/results.md").get().asFile.absolutePath,
    )
    systemProperties(
        System.getProperties()
            .entries
            .associate { it.key.toString() to it.value.toString() }
            .filterKeys { it.startsWith("icli.comparison.") }
    )
}

tasks.register<JavaExec>("stressComparisonReport") {
    description =
        "Runs stress comparison scenarios on the reusable test CLI and writes a Markdown report."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.github.ulviar.icli.comparison.StressComparisonRunner")
    jvmArgs(comparisonNativeAccessArg)
    args(
        layout.projectDirectory
            .dir("../context/comparison")
            .file("stress-results.md")
            .asFile
            .absolutePath
    )
    systemProperties(
        System.getProperties()
            .entries
            .associate { it.key.toString() to it.value.toString() }
            .filterKeys { it.startsWith("icli.comparison.") }
    )
}

tasks.register<JavaExec>("jmhBenchmark") {
    description = "Runs the full non-PTY JMH benchmark suite for comparison scenarios."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("jmhClasses"))
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs(comparisonNativeAccessArg, jmhUnsafeAccessArg)

    val resultFile = layout.buildDirectory.file("reports/jmh/results.json")
    doFirst { resultFile.get().asFile.parentFile.mkdirs() }
    args(
        "-rf",
        "json",
        "-rff",
        resultFile.get().asFile.absolutePath,
        "com.github.ulviar.icli.comparison.(OneShot|Streaming|LineSession|Expect|Pooled|ToolObservation).*",
    )
}

tasks.register<JavaExec>("jmhBenchmarkSmoke") {
    description = "Runs a short JMH smoke benchmark to verify benchmark wiring."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("jmhClasses"))
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs(comparisonNativeAccessArg, jmhUnsafeAccessArg)

    val resultFile = layout.buildDirectory.file("reports/jmh/smoke-results.json")
    doFirst { resultFile.get().asFile.parentFile.mkdirs() }
    args(
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
        resultFile.get().asFile.absolutePath,
        "com.github.ulviar.icli.comparison.OneShotProcessBenchmark.success",
    )
}

tasks.register<JavaExec>("jmhPtyBenchmark") {
    description = "Runs optional PTY JMH benchmarks on platforms with PTY capability."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("jmhClasses"))
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs(comparisonNativeAccessArg, jmhUnsafeAccessArg)

    val resultFile = layout.buildDirectory.file("reports/jmh/pty-results.json")
    doFirst { resultFile.get().asFile.parentFile.mkdirs() }
    args(
        "-rf",
        "json",
        "-rff",
        resultFile.get().asFile.absolutePath,
        "com.github.ulviar.icli.comparison.PtyProcessBenchmark",
    )
}

tasks.check { dependsOn("comparisonCheck") }

tasks.check { dependsOn("jmhCompileCheck") }
