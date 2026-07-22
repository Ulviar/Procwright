plugins { `java-library` }

val procwrightJavaRelease = rootProject.extra["procwrightJavaRelease"] as Int
val procwrightJavaVersion = rootProject.extra["procwrightJavaVersion"] as JavaVersion

dependencies {
    val consumerVersion = providers.gradleProperty("procwright.consumerVersion").orNull
    if (consumerVersion == null) {
        implementation(project(":"))
    } else {
        implementation("io.github.ulviar:procwright:$consumerVersion")
    }

    testImplementation(project(":procwright-test-cli"))
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = procwrightJavaVersion
    targetCompatibility = procwrightJavaVersion
    sourceSets.named("main") {
        java.srcDir(rootProject.layout.projectDirectory.dir("docs/examples/java"))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(procwrightJavaRelease)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
