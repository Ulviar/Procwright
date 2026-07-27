plugins { `java-library` }

dependencies {
    val consumerVersion = providers.gradleProperty("procwright.consumerVersion").orNull
    if (consumerVersion == null) {
        implementation(project(":procwright-integrations"))
    } else {
        implementation("io.github.ulviar:procwright-integrations:$consumerVersion")
    }
}

java {
    modularity.inferModulePath.set(true)
    sourceSets.named("main") {
        java.srcDir(rootProject.layout.projectDirectory.dir("docs/examples/integrations"))
    }
}

val runCanonicalIntegrationExample =
    tasks.register<JavaExec>("runCanonicalIntegrationExample") {
        description = "Runs the public integrations consumer example."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets.main.get().runtimeClasspath
        modularity.inferModulePath.set(true)
        mainModule.set("io.github.ulviar.procwright.integrations.consumer.example")
        mainClass.set("io.github.ulviar.procwright.examples.integration.JsonLineIntegrationExample")
        doFirst { systemProperty("java.class.path", sourceSets.main.get().runtimeClasspath.asPath) }
    }

val runTypedContentLengthExample =
    tasks.register<JavaExec>("runTypedContentLengthExample") {
        description = "Runs the typed Content-Length integrations example."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        classpath = sourceSets.main.get().runtimeClasspath
        modularity.inferModulePath.set(true)
        mainModule.set("io.github.ulviar.procwright.integrations.consumer.example")
        mainClass.set(
            "io.github.ulviar.procwright.examples.integration.TypedContentLengthJsonSessionExample"
        )
        doFirst { systemProperty("java.class.path", sourceSets.main.get().runtimeClasspath.asPath) }
    }

tasks.check { dependsOn(runCanonicalIntegrationExample, runTypedContentLengthExample) }
