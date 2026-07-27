val docsRequirementsLockCheck =
    tasks.register("docsRequirementsLockCheck") {
        description = "Checks that the hashed documentation lock matches top-level requirements."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        val requirements = layout.projectDirectory.file("docs/requirements.txt")
        val lock = layout.projectDirectory.file("docs/requirements.lock")
        inputs.files(requirements, lock)

        doLast {
            val requirementPattern = Regex("^([A-Za-z0-9_.-]+)==([^\\s\\\\]+)(?:\\s+\\\\)?$")
            fun normalizedName(name: String): String =
                name.lowercase().replace('_', '-').replace('.', '-')

            fun pins(file: File): Map<String, String> =
                file
                    .readLines()
                    .mapNotNull { line -> requirementPattern.matchEntire(line.trim()) }
                    .associate { match ->
                        normalizedName(match.groupValues[1]) to match.groupValues[2]
                    }

            val requiredPins = pins(requirements.asFile)
            val lockedPins = pins(lock.asFile)
            if (requiredPins.isEmpty()) {
                throw GradleException("docs/requirements.txt contains no exact package pins")
            }
            requiredPins.forEach { (name, version) ->
                val lockedVersion = lockedPins[name]
                if (lockedVersion != version) {
                    throw GradleException(
                        "docs/requirements.lock must pin $name==$version, found ${lockedVersion ?: "no entry"}"
                    )
                }
            }

            val lockLines = lock.asFile.readLines()
            val packageLineIndexes =
                lockLines.indices.filter { index ->
                    requirementPattern.matches(lockLines[index].trim())
                }
            packageLineIndexes.forEachIndexed { position, start ->
                val end = packageLineIndexes.getOrElse(position + 1) { lockLines.size }
                if (
                    lockLines.subList(start + 1, end).none { line ->
                        line.trimStart().startsWith("--hash=sha256:")
                    }
                ) {
                    throw GradleException(
                        "Documentation lock entry has no SHA-256 hash: ${lockLines[start].trim()}"
                    )
                }
            }
        }
    }

val preparedPublicDocs = layout.buildDirectory.dir("prepared-public-docs")
val kotlinDokkaHtml = project(":procwright-kotlin").layout.buildDirectory.dir("kdoc-validation")

val preparePublicDocs =
    tasks.register<Sync>("preparePublicDocs") {
        description = "Combines public Markdown and generated Java and Kotlin API docs for MkDocs."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn("publicJavaJavadocCheck", ":procwright-kotlin:dokkaGeneratePublicationHtml")

        from(layout.projectDirectory.dir("docs"))
        from(layout.buildDirectory.dir("docs/javadoc")) {
            exclude("**/*.md")
            into("api/java/core")
        }
        from(project(":procwright-integrations").layout.buildDirectory.dir("docs/javadoc")) {
            exclude("**/*.md")
            into("api/java/integrations")
        }
        from(kotlinDokkaHtml) { into("api/kotlin") }
        into(preparedPublicDocs)
    }

tasks.register<Exec>("publicDocsCheck") {
    description =
        "Builds the public MkDocs documentation site in strict mode and attaches generated API docs."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(preparePublicDocs, docsRequirementsLockCheck)

    val requirements = layout.projectDirectory.file("docs/requirements.lock")
    val requirementsSource = layout.projectDirectory.file("docs/requirements.txt")
    val config = layout.projectDirectory.file("mkdocs.yml")
    val output = layout.buildDirectory.dir("public-docs")

    inputs.file(requirements)
    inputs.file(requirementsSource)
    inputs.file(config)
    inputs.dir(layout.projectDirectory.dir("docs"))
    inputs.dir(preparedPublicDocs)
    outputs.dir(output)
    environment("PROCWRIGHT_DOCS_DIR", preparedPublicDocs.get().asFile.absolutePath)

    commandLine(
        "uv",
        "run",
        "--isolated",
        "--with-requirements",
        requirements.asFile.absolutePath,
        "python",
        "-m",
        "mkdocs",
        "build",
        "--strict",
        "--site-dir",
        output.get().asFile.absolutePath,
    )
}
