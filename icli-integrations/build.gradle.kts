import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
    signing
}

val icliJavaRelease = rootProject.extra["icliJavaRelease"] as Int
val icliJavaVersion = rootProject.extra["icliJavaVersion"] as JavaVersion

dependencies {
    api(project(":"))

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = icliJavaVersion
    targetCompatibility = icliJavaVersion
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "icli-integrations"
            pom {
                name.set("iCLI Integrations")
                description.set("Optional protocol and tool-adapter helpers for iCLI.")
                url.set("https://github.com/Ulviar/iCLI")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Ulviar/iCLI.git")
                    developerConnection.set("scm:git:https://github.com/Ulviar/iCLI.git")
                    url.set("https://github.com/Ulviar/iCLI")
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
    options.release.set(icliJavaRelease)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
