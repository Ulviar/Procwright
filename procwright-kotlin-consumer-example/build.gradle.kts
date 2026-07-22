import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

val procwrightJavaRelease = rootProject.extra["procwrightJavaRelease"] as Int
val procwrightJavaVersion = rootProject.extra["procwrightJavaVersion"] as JavaVersion

dependencies {
    val consumerVersion = providers.gradleProperty("procwright.consumerVersion").orNull
    if (consumerVersion == null) {
        implementation(project(":procwright-kotlin"))
    } else {
        implementation("io.github.ulviar:procwright-kotlin:$consumerVersion")
    }
}

java {
    sourceCompatibility = procwrightJavaVersion
    targetCompatibility = procwrightJavaVersion
    modularity.inferModulePath.set(true)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(procwrightJavaRelease)
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("docs/examples/kotlin"))
    }
    compilerOptions {
        jvmTarget.set(
            when (procwrightJavaRelease) {
                17 -> JvmTarget.JVM_17
                21 -> JvmTarget.JVM_21
                25 -> JvmTarget.JVM_25
                else ->
                    throw GradleException("Unsupported Kotlin JVM target $procwrightJavaRelease")
            }
        )
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

val kotlinConsumerModuleCheck =
    tasks.register("kotlinConsumerModuleCheck") {
        description = "Checks the packaged modular Kotlin consumer descriptor."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        val consumerJar = kotlinConsumerJar.flatMap { it.archiveFile }
        dependsOn(kotlinConsumerJar)
        inputs.file(consumerJar)

        doLast {
            val references = ModuleFinder.of(consumerJar.get().asFile.toPath()).findAll()
            if (references.size != 1) {
                throw GradleException(
                    "Kotlin consumer jar must contain exactly one module descriptor"
                )
            }
            val descriptor = references.single().descriptor()
            val expectedRequirements = setOf("io.github.ulviar.procwright.kotlin")
            val actualRequirements =
                descriptor
                    .requires()
                    .filterNot {
                        ModuleDescriptor.Requires.Modifier.MANDATED in it.modifiers() ||
                            ModuleDescriptor.Requires.Modifier.SYNTHETIC in it.modifiers()
                    }
                    .map { it.name() }
                    .toSet()
            if (
                descriptor.name() != "io.github.ulviar.procwright.kotlin.consumer.example" ||
                    descriptor.isAutomatic ||
                    descriptor.isOpen ||
                    descriptor.exports().isNotEmpty() ||
                    descriptor.opens().isNotEmpty() ||
                    descriptor.uses().isNotEmpty() ||
                    descriptor.provides().isNotEmpty() ||
                    actualRequirements != expectedRequirements
            ) {
                throw GradleException("Unexpected Kotlin consumer module descriptor: $descriptor")
            }
        }
    }

tasks.check { dependsOn(runKotlinConsumer, kotlinConsumerModuleCheck) }
