import org.gradle.api.publish.maven.MavenPublication
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":"))
    api("tools.jackson.core:jackson-databind:3.2.2")
    compileOnlyApi("org.jspecify:jspecify:1.0.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.jspecify:jspecify:1.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.named<Javadoc>("javadoc") {
    dependsOn(rootProject.tasks.named("javadoc"))
    val coreJavadoc = rootProject.layout.buildDirectory.dir("docs/javadoc")
    inputs.file(coreJavadoc.map { it.file("element-list") })
    (options as StandardJavadocDocletOptions).linksOffline(
        "https://ulviar.github.io/Procwright/api/java/core/",
        coreJavadoc.get().asFile.absolutePath,
    )
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
