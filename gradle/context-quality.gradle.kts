import java.nio.file.Path
import java.util.ArrayDeque

val repositoryRoot = layout.projectDirectory.asFile

fun contextHygieneFailures(
    repositoryPaths: Set<String>,
    markdownDocuments: Map<String, String>,
): List<String> {
    val linkPattern = Regex("(?<!!)\\[[^]\\n]*]\\(([^)\\n]+)\\)")
    val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    val forbiddenDirectories =
        setOf(
            "archive",
            "archives",
            "completed-plans",
            "history",
            "report",
            "reports",
            "transcript",
            "transcripts",
        )
    val forbiddenFileName =
        Regex(
            "(?:^|[-_])(?:audit-report|subagent-report|raw-report|transcript|completed-plan|closed-plan)(?:[-_]|$)"
        )
    val failures = mutableListOf<String>()

    repositoryPaths
        .filter { it.startsWith("context/") }
        .sorted()
        .forEach { path ->
            val relative = path.removePrefix("context/")
            val segments = relative.split('/')
            val lowerSegments = segments.map(String::lowercase)
            val stem = segments.last().substringBeforeLast('.').lowercase()
            if (
                lowerSegments.dropLast(1).any(forbiddenDirectories::contains) ||
                    forbiddenFileName.containsMatchIn(stem)
            ) {
                failures += "$path is a forbidden historical context location"
            }
        }

    val reachable = mutableSetOf("AGENTS.md", "context/README.md")
    val pending = ArrayDeque<String>()
    pending.addAll(reachable)
    while (pending.isNotEmpty()) {
        val source = pending.removeFirst()
        val text = markdownDocuments[source] ?: continue
        linkPattern.findAll(text).forEach { match ->
            val rawTarget = match.groupValues[1].trim().removeSurrounding("<", ">")
            val target = rawTarget.substringBefore('#')
            if (
                target.isBlank() ||
                    rawTarget.startsWith('#') ||
                    rawTarget.startsWith("//") ||
                    schemePattern.containsMatchIn(rawTarget)
            ) {
                return@forEach
            }
            val parent = Path.of(source).parent ?: Path.of("")
            val resolved =
                parent.resolve(target).normalize().toString().replace(File.separatorChar, '/')
            if (resolved in repositoryPaths && reachable.add(resolved)) {
                pending.addLast(resolved)
            }
        }
    }

    repositoryPaths
        .filter { it.startsWith("context/") && it !in reachable }
        .sorted()
        .forEach { path -> failures += "$path is unreachable from AGENTS.md or context/README.md" }
    return failures
}

val contextHygieneSelfTest =
    tasks.register("contextHygieneSelfTest") {
        description = "Proves that context hygiene rejects orphaned and historical material."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            val cleanPaths = setOf("AGENTS.md", "context/README.md", "context/active.md")
            val cleanDocuments =
                mapOf(
                    "AGENTS.md" to "[Context](context/README.md)",
                    "context/README.md" to "[Active](active.md)",
                    "context/active.md" to "# Active",
                )
            check(contextHygieneFailures(cleanPaths, cleanDocuments).isEmpty()) {
                "Context hygiene self-test rejected a reachable active document"
            }

            val orphanFailures =
                contextHygieneFailures(
                    cleanPaths + "context/orphan.md",
                    cleanDocuments + ("context/orphan.md" to "# Orphan"),
                )
            check(orphanFailures.any { it.contains("context/orphan.md is unreachable") }) {
                "Context hygiene self-test accepted an orphaned document"
            }

            for (hostilePath in
                listOf(
                    "context/archive/decision.md",
                    "context/reports/current.md",
                    "context/raw-audit-report.md",
                    "context/session-transcript.md",
                    "context/completed-plan.md",
                )) {
                val hostileDocuments =
                    cleanDocuments +
                        ("context/README.md" to
                            "[Active](active.md)\n[Hostile](${hostilePath.removePrefix("context/")})") +
                        (hostilePath to "# Hostile")
                val failures = contextHygieneFailures(cleanPaths + hostilePath, hostileDocuments)
                check(failures.any { it.contains("forbidden historical context location") }) {
                    "Context hygiene self-test accepted $hostilePath"
                }
            }
        }
    }

val contextHygieneCheck =
    tasks.register("contextHygieneCheck") {
        description =
            "Checks that current context is reachable and contains no historical material."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(contextHygieneSelfTest)

        val contextFiles = fileTree(layout.projectDirectory.dir("context"))
        val entrypoint = layout.projectDirectory.file("AGENTS.md")
        inputs.files(contextFiles, entrypoint)

        doLast {
            val files = (contextFiles.files + entrypoint.asFile).filter(File::isFile)
            val paths = files.map { it.relativeTo(repositoryRoot).invariantSeparatorsPath }.toSet()
            val documents =
                files
                    .filter { it.extension.equals("md", ignoreCase = true) }
                    .associate {
                        it.relativeTo(repositoryRoot).invariantSeparatorsPath to it.readText()
                    }
            val failures = contextHygieneFailures(paths, documents)
            if (failures.isNotEmpty()) {
                throw GradleException("Context hygiene violations:\n${failures.joinToString("\n")}")
            }
        }
    }

val contextLinksCheck =
    tasks.register("contextLinksCheck") {
        description = "Checks local Markdown links in AGENTS.md and project context."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(contextHygieneCheck)

        val markdownFiles =
            files(
                layout.projectDirectory.file("AGENTS.md"),
                fileTree(layout.projectDirectory.dir("context")) { include("**/*.md") },
            )
        inputs.files(markdownFiles)

        doLast {
            val linkPattern = Regex("(?<!!)\\[[^]\\n]*]\\(([^)\\n]+)\\)")
            val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
            val broken = mutableListOf<String>()
            markdownFiles.files.filter(File::isFile).sorted().forEach { source ->
                source.readLines().forEachIndexed { index, line ->
                    linkPattern.findAll(line).forEach { match ->
                        val rawTarget = match.groupValues[1].trim().removeSurrounding("<", ">")
                        val target = rawTarget.substringBefore('#')
                        if (
                            target.isBlank() ||
                                rawTarget.startsWith('#') ||
                                rawTarget.startsWith("//") ||
                                schemePattern.containsMatchIn(rawTarget)
                        ) {
                            return@forEach
                        }
                        if (!source.parentFile.resolve(target).exists()) {
                            broken +=
                                "${source.relativeTo(repositoryRoot)}:${index + 1} -> $rawTarget"
                        }
                    }
                }
            }
            if (broken.isNotEmpty()) {
                throw GradleException("Broken local context links:\n${broken.joinToString("\n")}")
            }
        }
    }
