plugins {
    java
    application
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("io.github.ulviar.procwright.testcli.TestCli") }

tasks.named<Javadoc>("javadoc") {
    enabled = false
    description = "Disabled because this project is an internal test CLI, not a published API."
}

tasks.withType<Test>().configureEach {
    systemProperty("procwright.repositoryRoot", rootProject.projectDir.absolutePath)
}
