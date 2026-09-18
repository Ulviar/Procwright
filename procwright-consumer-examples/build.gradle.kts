plugins { `java-library` }

dependencies {
    val consumerVersion = providers.gradleProperty("procwright.consumerVersion").orNull
    if (consumerVersion == null) {
        implementation(project(":"))
    } else {
        implementation("io.github.ulviar:procwright:$consumerVersion")
    }

    testImplementation(project(":procwright-test-cli"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceSets.named("main") {
        java.srcDir(rootProject.layout.projectDirectory.dir("docs/examples/java"))
    }
}
