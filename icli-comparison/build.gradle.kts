plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":"))
    implementation(project(":icli-integrations"))
    implementation("org.apache.commons:commons-exec:1.6.0")
    implementation("org.zeroturnaround:zt-exec:1.12")
    implementation("com.zaxxer:nuprocess:3.0.0")
    implementation("org.jetbrains.pty4j:pty4j:0.13.12")
    implementation("net.sf.expectit:expectit-core:0.9.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

application { mainClass.set("com.github.ulviar.icli.comparison.ComparisonRunner") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

tasks.register<JavaExec>("comparisonReport") {
    description = "Runs library comparison scenarios and writes a Markdown report."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
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
