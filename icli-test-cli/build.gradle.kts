plugins {
    java
    application
}

val icliJavaRelease = rootProject.extra["icliJavaRelease"] as Int
val icliJavaVersion = rootProject.extra["icliJavaVersion"] as JavaVersion

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = icliJavaVersion
    targetCompatibility = icliJavaVersion
}

application { mainClass.set("io.github.ulviar.icli.testcli.TestCli") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(icliJavaRelease)
}

tasks.named<Javadoc>("javadoc") {
    enabled = false
    description = "Disabled because this project is an internal test CLI, not a published API."
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
