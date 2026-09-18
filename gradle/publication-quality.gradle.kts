import groovy.json.JsonSlurper
import java.io.DataInputStream
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.w3c.dom.Element

private fun Element.directChild(name: String): Element? =
    (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().firstOrNull {
        child ->
        child.tagName == name
    }

private fun Element.requiredText(name: String, source: File): String =
    directChild(name)?.textContent?.trim()?.takeIf(String::isNotEmpty)
        ?: throw GradleException("Generated POM is missing $name: $source")

val publicRuntimeProjects =
    setOf(project.path, ":procwright-kotlin", ":procwright-integrations").map(::project)
val forbiddenProcessLibraryCoordinates =
    setOf(
        "org.apache.commons:commons-exec",
        "org.zeroturnaround:zt-exec",
        "com.zaxxer:nuprocess",
        "org.jetbrains.pty4j:pty4j",
        "net.sf.expectit:expectit-core",
        "org.openjdk.jmh:jmh-core",
        "org.openjdk.jmh:jmh-generator-annprocess",
    )

val externalLibraryBoundaryCheck =
    tasks.register("externalLibraryBoundaryCheck") {
        description =
            "Verifies public-module dependency boundaries, including compile-only JSpecify and optional Jackson."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            val jspecifyDependencies =
                configurations.getByName("compileOnlyApi").dependencies.filter {
                    it.group == "org.jspecify" && it.name == "jspecify"
                }
            if (
                jspecifyDependencies.size != 1 || jspecifyDependencies.single().version != "1.0.1"
            ) {
                throw GradleException(
                    "Core compileOnlyApi must contain exactly org.jspecify:jspecify:1.0.1"
                )
            }

            publicRuntimeProjects.forEach { checkedProject ->
                val runtimeClasspath = checkedProject.configurations.findByName("runtimeClasspath")
                if (runtimeClasspath != null && runtimeClasspath.isCanBeResolved) {
                    runtimeClasspath.incoming.resolutionResult.allComponents.forEach { component ->
                        val id = component.moduleVersion
                        if (id != null) {
                            val coordinate = "${id.group}:${id.name}"
                            if (coordinate == "org.jspecify:jspecify") {
                                throw GradleException(
                                    "Compile-only JSpecify metadata leaked into ${checkedProject.path}:runtimeClasspath"
                                )
                            }
                            if (
                                checkedProject.path != ":procwright-integrations" &&
                                    (id.group.startsWith("com.fasterxml.jackson") ||
                                        id.group.startsWith("tools.jackson"))
                            ) {
                                throw GradleException(
                                    "Jackson dependency $coordinate leaked into ${checkedProject.path}:runtimeClasspath"
                                )
                            }
                            if (coordinate in forbiddenProcessLibraryCoordinates) {
                                throw GradleException(
                                    "External process runtime $coordinate leaked into ${checkedProject.path}:runtimeClasspath"
                                )
                            }
                        }
                    }
                }

                checkedProject.configurations.forEach { configuration ->
                    configuration.dependencies.forEach { dependency ->
                        val coordinate = "${dependency.group}:${dependency.name}"
                        if (coordinate in forbiddenProcessLibraryCoordinates) {
                            throw GradleException(
                                "External process runtime $coordinate leaked into ${checkedProject.path}:${configuration.name}"
                            )
                        }
                    }
                }
            }
        }
    }

val publicPublications =
    mapOf(
            ":" to "procwright",
            ":procwright-integrations" to "procwright-integrations",
            ":procwright-kotlin" to "procwright-kotlin",
        )
        .map { (projectPath, artifactId) -> project(projectPath) to artifactId }
val expectedMavenGroup = group.toString()

val publicationStructureCheck =
    tasks.register("publicationStructureCheck") {
        description =
            "Checks registry-independent artifact and POM structure for every public module."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        publicPublications.forEach { (checkedProject, _) ->
            val projectPath = checkedProject.path
            val prefix = if (projectPath == ":") ":" else "$projectPath:"
            dependsOn(
                "${prefix}jar",
                "${prefix}sourcesJar",
                "${prefix}javadocJar",
                "${prefix}generatePomFileForMavenJavaPublication",
                "${prefix}generateMetadataFileForMavenJavaPublication",
            )
        }

        doLast {
            publicPublications.forEach { (checkedProject, artifactId) ->
                val projectPath = checkedProject.path
                val publication =
                    checkedProject.extensions
                        .getByType(PublishingExtension::class.java)
                        .publications
                        .getByName("mavenJava") as MavenPublication
                val classifiers =
                    publication.artifacts.map { artifact -> artifact.classifier }.toSet()
                if (
                    publication.artifacts.size != 3 ||
                        classifiers != setOf(null, "sources", "javadoc")
                ) {
                    throw GradleException(
                        "$projectPath publication must contain main, sources, and javadoc artifacts: $classifiers"
                    )
                }
                publication.artifacts.forEach { artifact ->
                    if (artifact.extension != "jar") {
                        throw GradleException(
                            "Publication artifact must be a JAR: ${artifact.file}"
                        )
                    }
                    if (!artifact.file.isFile || artifact.file.length() == 0L) {
                        throw GradleException(
                            "Publication artifact is missing or empty: ${artifact.file}"
                        )
                    }
                }

                val mainJar = publication.artifacts.single { it.classifier == null }.file
                JarFile(mainJar).use { jar ->
                    val classes =
                        jar.entries().asSequence().filter { it.name.endsWith(".class") }.toList()
                    if (classes.isEmpty()) {
                        throw GradleException("Public JAR contains no classes: $mainJar")
                    }
                    classes.forEach { entry ->
                        DataInputStream(jar.getInputStream(entry)).use { input ->
                            val magic = input.readInt()
                            val minor = input.readUnsignedShort()
                            val major = input.readUnsignedShort()
                            if (magic != 0xCAFEBABE.toInt() || major != 69 || minor != 0) {
                                throw GradleException(
                                    "Public class must target stable Java 25: $mainJar!/${entry.name} ($major.$minor)"
                                )
                            }
                        }
                    }
                }
                val metadataFile =
                    checkedProject.tasks
                        .named(
                            "generateMetadataFileForMavenJavaPublication",
                            GenerateModuleMetadata::class.java,
                        )
                        .get()
                        .outputFile
                        .get()
                        .asFile
                val metadata = JsonSlurper().parse(metadataFile) as Map<*, *>
                val variants = metadata["variants"] as List<*>
                val libraryVariants =
                    variants.filterIsInstance<Map<*, *>>().filter { variant ->
                        val attributes = variant["attributes"] as Map<*, *>
                        attributes["org.gradle.category"] == "library"
                    }
                if (
                    libraryVariants.isEmpty() ||
                        libraryVariants.any { variant ->
                            (variant["attributes"] as Map<*, *>)["org.gradle.jvm.version"] != 25
                        }
                ) {
                    throw GradleException(
                        "Every published library variant must require Java 25: $metadataFile"
                    )
                }

                val pomTask =
                    checkedProject.tasks
                        .named(
                            "generatePomFileForMavenJavaPublication",
                            GenerateMavenPom::class.java,
                        )
                        .get()
                val pomFile = pomTask.destination
                val documentBuilder =
                    DocumentBuilderFactory.newInstance().apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    }
                val root = documentBuilder.newDocumentBuilder().parse(pomFile).documentElement
                if (root.requiredText("groupId", pomFile) != expectedMavenGroup) {
                    throw GradleException("Generated POM has the wrong groupId: $pomFile")
                }
                if (root.requiredText("artifactId", pomFile) != artifactId) {
                    throw GradleException("Generated POM has the wrong artifactId: $pomFile")
                }
                root.requiredText("version", pomFile)
                root.requiredText("name", pomFile)
                root.requiredText("description", pomFile)
                root.requiredText("url", pomFile)
                val license =
                    root.directChild("licenses")?.directChild("license")
                        ?: throw GradleException("Generated POM has no license: $pomFile")
                license.requiredText("name", pomFile)
                license.requiredText("url", pomFile)
                val scm =
                    root.directChild("scm")
                        ?: throw GradleException("Generated POM has no SCM: $pomFile")
                scm.requiredText("connection", pomFile)
                scm.requiredText("developerConnection", pomFile)
                scm.requiredText("url", pomFile)
                val developer =
                    root.directChild("developers")?.directChild("developer")
                        ?: throw GradleException("Generated POM has no developer: $pomFile")
                developer.requiredText("id", pomFile)
                developer.requiredText("name", pomFile)
            }
        }
    }
