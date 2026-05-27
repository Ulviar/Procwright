plugins { `java-library` }

val procwrightJavaRelease = rootProject.extra["procwrightJavaRelease"] as Int
val procwrightJavaVersion = rootProject.extra["procwrightJavaVersion"] as JavaVersion

dependencies {
    implementation(project(":"))

    testImplementation(project(":procwright-test-cli"))
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = procwrightJavaVersion
    targetCompatibility = procwrightJavaVersion
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(procwrightJavaRelease)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
