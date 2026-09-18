import org.gradle.api.tasks.bundling.Jar

plugins { id("org.jetbrains.kotlin.jvm") }

dependencies {
    val consumerVersion = providers.gradleProperty("procwright.consumerVersion").orNull
    if (consumerVersion == null) {
        implementation(project(":procwright-kotlin"))
    } else {
        implementation("io.github.ulviar:procwright-kotlin:$consumerVersion")
    }
}

java { modularity.inferModulePath.set(true) }

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("docs/examples/kotlin"))
    }
    compilerOptions {
        freeCompilerArgs.add("-Xjspecify-annotations=strict")
    }
}

val kotlinConsumerJar = tasks.named<Jar>("jar")
val kotlinConsumerRuntimeClasspath =
    files(kotlinConsumerJar.flatMap { it.archiveFile }) + configurations.runtimeClasspath.get()

val runKotlinConsumer =
    tasks.register<JavaExec>("runKotlinConsumer") {
        description = "Runs the standalone Kotlin external consumer fixture."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(kotlinConsumerJar)
        classpath = kotlinConsumerRuntimeClasspath
        modularity.inferModulePath.set(true)
        mainModule.set("io.github.ulviar.procwright.kotlin.consumer.example")
        mainClass.set("io.github.ulviar.procwright.consumer.kotlin.KotlinConsumer")
        doFirst { systemProperty("java.class.path", kotlinConsumerRuntimeClasspath.asPath) }
    }

tasks.check { dependsOn(runKotlinConsumer) }
