plugins {
    java
    application
}

val procwrightJavaRelease = rootProject.extra["procwrightJavaRelease"] as Int
val procwrightJavaVersion = rootProject.extra["procwrightJavaVersion"] as JavaVersion

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = procwrightJavaVersion
    targetCompatibility = procwrightJavaVersion
}

application { mainClass.set("io.github.ulviar.procwright.testcli.TestCli") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(procwrightJavaRelease)
}

tasks.named<Javadoc>("javadoc") {
    enabled = false
    description = "Disabled because this project is an internal test CLI, not a published API."
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("procwright.repositoryRoot", rootProject.projectDir.absolutePath)
}
