import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":"))
    api("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    compileOnlyApi("org.jspecify:jspecify:1.0.0")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.jspecify:jspecify:1.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "procwright-integrations"
            pom {
                name.set("Procwright Integrations")
                description.set(
                    "Optional JSON and byte-framing adapters for Procwright protocol sessions."
                )
            }
        }
    }
}
