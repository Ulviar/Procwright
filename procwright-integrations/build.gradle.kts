import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
    signing
}

val procwrightJavaRelease = rootProject.extra["procwrightJavaRelease"] as Int
val procwrightJavaVersion = rootProject.extra["procwrightJavaVersion"] as JavaVersion

dependencies {
    api(project(":"))
    api("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = procwrightJavaVersion
    targetCompatibility = procwrightJavaVersion
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
                description.set("Optional protocol and tool-adapter helpers for Procwright.")
                url.set("https://github.com/Ulviar/Procwright")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Ulviar/Procwright.git")
                    developerConnection.set("scm:git:https://github.com/Ulviar/Procwright.git")
                    url.set("https://github.com/Ulviar/Procwright")
                }
                developers {
                    developer {
                        id.set("Ulviar")
                        name.set("Ulviar")
                    }
                }
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (!signingKey.isNullOrBlank() && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(procwrightJavaRelease)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
