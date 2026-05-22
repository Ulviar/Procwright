plugins { `java-library` }

val icliJavaRelease = rootProject.extra["icliJavaRelease"] as Int
val icliJavaVersion = rootProject.extra["icliJavaVersion"] as JavaVersion

dependencies {
    implementation(project(":"))

    testImplementation(project(":icli-test-cli"))
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = icliJavaVersion
    targetCompatibility = icliJavaVersion
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(icliJavaRelease)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
