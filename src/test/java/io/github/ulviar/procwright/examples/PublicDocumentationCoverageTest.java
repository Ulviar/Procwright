/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.InteractiveScenario;
import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.ProtocolSessionScenario;
import io.github.ulviar.procwright.RunScenario;
import io.github.ulviar.procwright.StreamScenario;
import io.github.ulviar.procwright.terminal.PtyProvider;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublicDocumentationCoverageTest {

    private static final Path DOCS = Path.of("docs");
    private static final Pattern FENCED_CODE = Pattern.compile("(?ms)^```(java|kotlin)\\R(.*?)^```[ \\t]*$");
    private static final Pattern SNIPPET_INCLUDE = Pattern.compile("--8<-- \\\"([^\\\"]+)\\\"");
    private static final Pattern CORE_EXAMPLE_FENCE = Pattern.compile(
            "(?ms)^<!-- procwright-example: (examples/java/[^\\r\\n]+\\.java) -->\\R" + "```java\\R(.*?)^```[ \\t]*$");
    private static final Pattern CORE_EXAMPLE_MARKER_AT_END =
            Pattern.compile("(?m)^<!-- procwright-example: (examples/java/[^\\r\\n]+\\.java) -->\\R\\z");
    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("(?<!!)\\[[^]]+\\]\\(([^ )]+)(?: \\\"[^\\\"]*\\\")?\\)");
    private static final Pattern DIRECT_EXPECT_TERMINAL = Pattern.compile("\\.expect\\s*\\(\\s*[^)]");
    private static final Pattern EXECUTE_WITH_ARGUMENTS = Pattern.compile("\\.execute\\s*\\(\\s*[^)]");
    private static final Pattern CALLBACK_API_REFERENCE =
            Pattern.compile("`((?:CommandService|RunScenario\\.Draft|InteractiveScenario\\.Draft|"
                    + "LineSessionScenario\\.(?:Draft|PoolDraft)|ProtocolSessionScenario\\.(?:Draft|PoolDraft)|"
                    + "StreamScenario\\.Draft)\\.[A-Za-z][A-Za-z0-9]*)`");
    private static final String PSEUDOCODE_MARKER = "<!-- procwright-docs: pseudocode -->";
    private static final Set<Path> GENERATED_API_ROUTES = Set.of(
            Path.of("docs/api/java/core/index.html"),
            Path.of("docs/api/java/integrations/index.html"),
            Path.of("docs/api/kotlin/index.html"));
    private static final List<Class<?>> CALLBACK_API_OWNERS = List.of(
            CommandService.class,
            RunScenario.Draft.class,
            InteractiveScenario.Draft.class,
            LineSessionScenario.Draft.class,
            LineSessionScenario.PoolDraft.class,
            ProtocolSessionScenario.Draft.class,
            ProtocolSessionScenario.PoolDraft.class,
            StreamScenario.Draft.class);
    private static final Set<String> PROCESS_API_MARKERS = Set.of(
            "io.github.ulviar.procwright",
            "Procwright.",
            "CommandSpec.",
            "RunScenario.",
            "InteractiveScenario.",
            "LineSessionScenario.",
            "ProtocolSessionScenario.",
            "StreamScenario.",
            "session.expect()",
            "protocolAdapterFactory");
    private static final List<String> RETIRED_API_TOKENS = List.of(
            "CommandInvocation",
            "SessionInvocation",
            "LineSessionInvocation",
            "ProtocolSessionInvocation",
            "StreamInvocation",
            "RunOptions",
            "SessionOptions",
            "LineSessionOptions",
            "ProtocolSessionOptions",
            "ExpectOptions",
            "StreamOptions",
            "PooledLineSessionOptions",
            "PooledProtocolSessionOptions",
            "DiagnosticsOptions",
            "configuredBy",
            "CommandSpec.builder",
            "PooledLineSessionScenario",
            "PooledProtocolSessionScenario",
            "ReusableProtocolSessionScenario",
            "withOpenStdin",
            "withClosedStdinOnStart",
            "protocolAdapter<",
            "protocolSession(adapter)",
            ".pooled {");

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyPublicScenarioAndTaskPageShowsCanonicalCode() throws Exception {
        for (Path directory : List.of(Path.of("docs/scenarios"), Path.of("docs/how-to"))) {
            try (var files = Files.list(directory)) {
                for (Path page : files.filter(path -> path.toString().endsWith(".md"))
                        .filter(path -> !path.getFileName().toString().equals("index.md"))
                        .toList()) {
                    assertTrue(
                            hasCanonicalExample(read(page)),
                            () -> page + " must show a compile-tested canonical source");
                }
            }
        }
    }

    @Test
    void coreProcessApiFencesUseCanonicalSources() throws Exception {
        for (Path page : markdownFiles(DOCS)) {
            String text = read(page);
            if (!CORE_EXAMPLE_FENCE.matcher(text).find()) {
                continue;
            }
            Matcher blocks = FENCED_CODE.matcher(text);
            while (blocks.find()) {
                String body = blocks.group(2);
                if (PROCESS_API_MARKERS.stream().noneMatch(body::contains)) {
                    continue;
                }
                if (isExplicitPseudocode(text, blocks.start())) {
                    continue;
                }
                assertTrue(
                        SNIPPET_INCLUDE.matcher(body).find() || hasCoreExampleMarker(text, blocks.start()),
                        () -> page + " contains a standalone Procwright " + blocks.group(1) + " snippet");
            }
        }
    }

    @Test
    void embeddedCoreJavaExamplesExactlyMatchCompiledSources() throws Exception {
        ArrayList<Path> pages = new ArrayList<>(markdownFiles(DOCS));
        pages.add(Path.of("README.md"));

        for (Path page : pages) {
            String text = read(page);
            Matcher includes = SNIPPET_INCLUDE.matcher(text);
            while (includes.find()) {
                assertFalse(
                        includes.group(1).startsWith("examples/java/"),
                        () -> page + " hides a core Java example behind an include directive");
            }

            Matcher examples = CORE_EXAMPLE_FENCE.matcher(text);
            while (examples.find()) {
                Path source = DOCS.resolve(examples.group(1)).normalize();
                assertTrue(source.startsWith(DOCS), () -> page + " example escapes docs: " + examples.group(1));
                assertTrue(Files.isRegularFile(source), () -> page + " example is missing: " + examples.group(1));
                assertEquals(read(source), examples.group(2), () -> page + " example drifted from " + source);
            }
        }
    }

    @Test
    void everySnippetIsLocalAndResolvesInsideDocs() throws Exception {
        for (Path page : markdownFiles(DOCS)) {
            Matcher includes = SNIPPET_INCLUDE.matcher(read(page));
            while (includes.find()) {
                String target = includes.group(1);
                assertFalse(target.contains("://"), () -> page + " must not download snippets");
                Path resolved = DOCS.resolve(target).normalize();
                assertTrue(resolved.startsWith(DOCS), () -> page + " snippet escapes docs: " + target);
                assertTrue(Files.isRegularFile(resolved), () -> page + " snippet is missing: " + target);
            }
        }
    }

    @Test
    void localMarkdownLinksResolve() throws Exception {
        ArrayList<Path> pages = new ArrayList<>(markdownFiles(DOCS));
        pages.add(Path.of("README.md"));

        for (Path page : pages) {
            Matcher links = MARKDOWN_LINK.matcher(read(page));
            while (links.find()) {
                String destination = links.group(1);
                if (destination.startsWith("http://")
                        || destination.startsWith("https://")
                        || destination.startsWith("mailto:")
                        || destination.startsWith("#")) {
                    continue;
                }
                String pathPart = destination.split("#", 2)[0];
                Path resolved = page.getParent() == null
                        ? Path.of(pathPart).normalize()
                        : page.getParent().resolve(pathPart).normalize();
                if (GENERATED_API_ROUTES.contains(resolved)) {
                    continue;
                }
                assertTrue(Files.exists(resolved), () -> page + " has a broken local link: " + destination);
            }
        }
    }

    @Test
    void mkdocsRestrictsSnippetLoadingToLocalDocs() throws Exception {
        String config = read(Path.of("mkdocs.yml"));

        assertTrue(config.contains("pymdownx.snippets:"));
        assertTrue(config.contains("- docs"));
        assertTrue(config.contains("check_paths: true"));
        assertTrue(config.contains("restrict_base_path: true"));
        assertTrue(config.contains("url_download: false"));
    }

    @Test
    void publicDocsDoNotReferenceRetiredApi() throws Exception {
        ArrayList<Path> pages = new ArrayList<>(publicDocumentationSources());
        pages.add(Path.of("README.md"));

        for (Path page : pages) {
            String text = read(page);
            for (String token : RETIRED_API_TOKENS) {
                assertFalse(text.contains(token), () -> page + " references retired API token `" + token + "`");
            }
            assertFalse(
                    DIRECT_EXPECT_TERMINAL.matcher(text).find(), () -> page + " uses direct Session.expect terminal");
            assertFalse(EXECUTE_WITH_ARGUMENTS.matcher(text).find(), () -> page + " passes arguments to execute()");
        }
    }

    @Test
    void readmeJavaExampleCompilesAgainstCurrentCore() throws Exception {
        Matcher blocks = FENCED_CODE.matcher(read(Path.of("README.md")));
        String source = null;
        while (blocks.find()) {
            if (blocks.group(1).equals("java")) {
                source = blocks.group(2);
                break;
            }
        }
        assertNotNull(source, "README must contain one copyable Java example");

        Path sourceFile = temporaryDirectory.resolve("RunExample.java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "documentation tests require a JDK");
        int exitCode = compiler.run(
                null,
                null,
                null,
                "--release",
                "17",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                temporaryDirectory.toString(),
                sourceFile.toString());

        assertEquals(0, exitCode, "README Java example must compile against the current public API");
    }

    @Test
    void canonicalSourcesAreWiredIntoExternalConsumerBuilds() throws Exception {
        String coreBuild = read(Path.of("procwright-consumer-examples/build.gradle.kts"));
        String kotlinBuild = read(Path.of("procwright-kotlin-consumer-example/build.gradle.kts"));
        String integrationsBuild = read(Path.of("procwright-integrations-consumer-example/build.gradle.kts"));

        assertTrue(coreBuild.contains("docs/examples/java"));
        assertTrue(kotlinBuild.contains("docs/examples/kotlin"));
        assertTrue(integrationsBuild.contains("docs/examples/integrations"));
    }

    @Test
    void canonicalExamplesKeepCriticalLifecycleAndEncodingProofs() throws Exception {
        String run = read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/RunExample.java"));
        assertTrue(run.contains("System.getProperty(\"java.home\")"));
        assertTrue(run.contains("result.exitCode()"));
        assertTrue(run.contains("CapturePolicy.bounded(256 * 1024)"));
        assertTrue(run.contains("result.timedOut()"));
        assertTrue(run.contains("result.stdoutTruncated()"));
        assertTrue(run.contains("result.stderrTruncated()"));
        assertTrue(run.indexOf("System.out.print(result.stdout())") < run.indexOf("if (!result.succeeded())"));
        assertFalse(run.contains("ExampleSupport"));

        String stopHung =
                read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/StopHungCommandExample.java"));
        assertTrue(stopHung.contains(".withTimeout("));
        assertTrue(stopHung.contains(".withShutdown(ShutdownPolicy.interruptThenKill("));
        assertTrue(stopHung.contains("result.timedOut()"));

        String stopHungGuide = read(Path.of("docs/how-to/stop-hung-processes.md"));
        assertTrue(
                stopHungGuide.contains(
                        "<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/StopHungCommandExample.java -->"));

        String runFailure =
                read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/RunFailureExample.java"));
        assertTrue(runFailure.contains("import io.github.ulviar.procwright.command.CommandExecutionException;"));
        assertTrue(runFailure.contains("failure.reason() == LAUNCH_FAILED"));
        assertFalse(runFailure.contains("failure.result()"));

        String interactive =
                read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/InteractiveExample.java"));
        assertTrue(interactive.contains("Executors.newFixedThreadPool(2)"));
        assertTrue(interactive.contains("session.stdout().readAllBytes()"));
        assertTrue(interactive.contains("session.stderr().readAllBytes()"));
        assertTrue(interactive.contains("orTimeout(5, TimeUnit.SECONDS)"));

        String protocol =
                read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/ProtocolSessionExample.java"));
        assertTrue(protocol.contains("LengthLineFrameAdapter::new"));
        assertTrue(protocol.contains("ProtocolSession<DocumentRequest, DocumentResponse>"));
        assertTrue(protocol.contains("new DocumentRequest("));
        assertTrue(protocol.contains("DocumentResponse response"));
        assertTrue(protocol.contains("CharsetPolicy.report(StandardCharsets.UTF_8)"));
        assertTrue(protocol.codePoints().anyMatch(codePoint -> codePoint > 0x7f));

        String protocolAdapter =
                read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/LengthLineFrameAdapter.java"));
        assertTrue(protocolAdapter.contains("ProtocolAdapter<DocumentRequest, DocumentResponse>"));
        assertTrue(protocolAdapter.contains("writer.write(request.text())"));
        assertTrue(protocolAdapter.contains("readTextExactly(length, MAX_BODY_CHARS)"));

        String protocolMessages =
                read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/DocumentProtocol.java"));
        assertTrue(protocolMessages.contains("public record DocumentRequest(String text)"));
        assertTrue(protocolMessages.contains("public record DocumentResponse(String text)"));

        String linePool = read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java"));
        assertPoolExampleUsesTryWithResources(linePool);
        assertTrue(linePool.contains(".withRequestTimeout("));
        assertTrue(linePool.contains(".withMaxRequestBytes("));
        assertTrue(linePool.contains(".withMaxRequestChars("));
        assertTrue(linePool.contains(".withMaxLineChars("));
        assertTrue(linePool.contains(".withMaxResponseLines("));
        assertTrue(linePool.contains(".withMaxResponseChars("));
        assertTrue(linePool.contains(".withStdoutBacklogLines("));
        assertTrue(linePool.contains(".withStdoutBacklogChars("));
        assertTrue(linePool.contains(".withAcquireTimeout("));
        assertTrue(linePool.contains(".withHookTimeout("));
        assertTrue(linePool.contains(".withCloseTimeout("));
        assertPoolExampleUsesTryWithResources(
                read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/ProtocolPoolExample.java")));

        String kotlin =
                read(Path.of("docs/examples/kotlin/io/github/ulviar/procwright/examples/kotlin/KotlinExample.kt"));
        assertTrue(kotlin.contains("protocolAdapterFactory"));
        assertTrue(kotlin.contains(".openFlow()"));
        assertTrue(kotlin.contains(".executeAwait()"));
        assertTrue(kotlin.contains(".open()"));
        assertTrue(kotlin.contains(".collect { chunk ->"));
        assertTrue(kotlin.contains("throw version.toException()"));
        assertTrue(kotlin.contains(".open().use { session ->"));
        assertEquals(
                2L,
                kotlin.lines()
                        .filter(line -> line.contains("session.requestAwait("))
                        .count());
        assertFalse(kotlin.contains(".toList()"));

        String protocolPool =
                read(Path.of("docs/examples/java/io/github/ulviar/procwright/examples/ProtocolPoolExample.java"));
        assertTrue(protocolPool.contains(".withReadiness(worker ->"));
        assertTrue(protocolPool.contains(".withReadinessTimeout("));
        assertTrue(protocolPool.contains(".withRequestTimeout("));
        assertTrue(protocolPool.contains(".withMaxRequestBytes("));
        assertTrue(protocolPool.contains(".withMaxRequestChars("));
        assertTrue(protocolPool.contains(".withMaxResponseBytes("));
        assertTrue(protocolPool.contains(".withMaxResponseChars("));
        assertTrue(protocolPool.contains(".withOutputBacklogLimit("));
        assertTrue(protocolPool.contains("CharsetPolicy.report(StandardCharsets.UTF_8)"));
    }

    @Test
    void documentationPreservesFlowOutcomeAndAdapterConcurrencyWarnings() throws Exception {
        String kotlin = normalizeWhitespace(read(Path.of("docs/reference/kotlin-api.md")));
        assertTrue(kotlin.contains("emits only `StreamChunk`"));
        assertTrue(kotlin.contains("exit code"));
        assertTrue(kotlin.contains("timeout or caller close"));
        assertTrue(kotlin.contains("diagnostic transcript"));
        assertTrue(kotlin.contains("listen().onOutput(...).open()"));
        assertTrue(kotlin.contains("awaitExit()` or `onExit()"));
        assertTrue(kotlin.contains("replaces any listener previously set with `onOutput`"));
        assertTrue(kotlin.contains("may invoke the supplier and configuration block concurrently"));
        assertTrue(kotlin.contains("return a fresh adapter each time"));
        assertTrue(kotlin.contains("Mutable state captured from outside remains shared"));
        assertTrue(kotlin.contains("kotlin(\"jvm\") version \"2.3.21\""));
        assertTrue(kotlin.contains("mavenLocal()"));
        assertTrue(kotlin.contains("jvmToolchain(17)"));
        assertTrue(kotlin.contains("core, Kotlin standard library, and coroutines transitively"));

        String protocol = normalizeWhitespace(read(Path.of("docs/scenarios/protocol-session.md")));
        assertTrue(protocol.contains("Only mutable state created by or owned by that adapter is isolated"));
        assertTrue(protocol.contains("Factory calls may overlap during concurrent opens or pool startup"));
        assertTrue(protocol.contains("state captured from outside remains shared"));
        assertTrue(protocol.contains("synchronized or avoided"));
        assertFalse(protocol.contains("mutable decoder state is never shared"));

        String defaults = normalizeWhitespace(read(Path.of("docs/reference/defaults.md")));
        assertTrue(defaults.contains("| Request timeout | 5 seconds |"));
        assertTrue(defaults.contains("| Unread output backlog | 1 MiB |"));
        assertTrue(defaults.contains("UTF-8 with malformed and unmappable input replaced"));

        String pooling = normalizeWhitespace(read(Path.of("docs/scenarios/pooling.md")));
        assertTrue(pooling.contains("adapter factory concurrently"));
        assertTrue(pooling.contains("return a fresh adapter for every worker"));
        assertTrue(pooling.contains("externally captured mutable state remains shared"));
        assertTrue(pooling.contains("synchronized or avoided"));
    }

    @Test
    void requestAdmissionAndCallbackCapabilityBoundariesStayExplicit() throws Exception {
        String protocol = normalizeWhitespace(read(Path.of("docs/scenarios/protocol-session.md")));
        assertTrue(protocol.contains("waiting for the serialized request slot happens before adapter admission"));
        assertTrue(protocol.contains("writes no request bytes and leaves the direct session open"));
        assertTrue(protocol.contains("`ProtocolWriter` and `ProtocolReader` are callback-scoped and thread-confined"));

        String line = normalizeWhitespace(read(Path.of("docs/scenarios/line-session.md")));
        assertTrue(line.contains("`ResponseDecoder.Reader` is callback-scoped and thread-confined"));

        String kotlin = normalizeWhitespace(read(Path.of("docs/reference/kotlin-api.md")));
        assertTrue(kotlin.contains(
                "Cancelling while waiting for a direct line or protocol request slot abandons only that call"));
        assertTrue(kotlin.contains("A line request remains retryable until stdin handoff"));
        assertTrue(kotlin.contains("a protocol request becomes terminal once it acquires the serialized slot"));
    }

    @Test
    void callbackConcurrencyReferenceInventoriesEveryRetainedCallbackSurface() throws Exception {
        Set<String> callbackApi = new TreeSet<>();
        for (Class<?> owner : CALLBACK_API_OWNERS) {
            Arrays.stream(owner.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> Arrays.stream(method.getParameterTypes())
                            .anyMatch(PublicDocumentationCoverageTest::isRetainedCallbackType))
                    .map(method -> publicTypeName(owner) + "." + method.getName())
                    .forEach(callbackApi::add);
        }

        Set<String> documentedApi = new TreeSet<>();
        Matcher references = CALLBACK_API_REFERENCE.matcher(read(Path.of("docs/reference/policies.md")));
        while (references.find()) {
            documentedApi.add(references.group(1));
        }

        assertEquals(callbackApi, documentedApi, "callback concurrency reference must cover the public callback API");
    }

    @Test
    void streamingDocumentationKeepsTheListenStdinBoundaryExplicit() throws Exception {
        String streaming = normalizeWhitespace(read(Path.of("docs/scenarios/streaming.md")));

        assertTrue(streaming.contains("`listen()` closes stdin when the process starts"));
        assertTrue(streaming.contains("[`interactive()`](interactive.md)"));
    }

    @Test
    void poolDocumentationKeepsSelectionTimingAndRetirementContractsExplicit() throws Exception {
        String readme = normalizeWhitespace(read(Path.of("README.md")));
        assertTrue(readme.contains("Direct line and protocol sessions are already long-lived"));
        assertTrue(readme.contains("stable affinity"));
        assertTrue(readme.contains("requests are independent"));

        String policies = normalizeWhitespace(read(Path.of("docs/reference/policies.md")));
        assertTrue(policies.contains("A pooled call has no single overall deadline"));
        assertTrue(policies.contains("health check is capped by both the remaining acquire time"));
        assertTrue(policies.contains("Request encoding and response decoding consume the request deadline"));
        assertTrue(policies.contains("reset has its own `withHookTimeout(...)` budget"));

        String pooling = normalizeWhitespace(read(Path.of("docs/scenarios/pooling.md")));
        assertTrue(pooling.contains("Acquire fails before lease"));
        assertTrue(pooling.contains("Request fails after lease"));
        assertTrue(pooling.contains("Health fails during acquire"));
        assertTrue(pooling.contains("Reset fails after a successful response"));
        assertTrue(pooling.contains("`DRAIN_TIMEOUT` or `INTERRUPTED`"));
        assertTrue(pooling.contains("retires as `RESET_FAILED`"));
        assertTrue(pooling.contains("Do not blindly retry handed-off, non-idempotent work"));
    }

    @Test
    void generatedApiNavigationTargetsPreparedSitePaths() throws Exception {
        String navigation = read(Path.of("mkdocs.yml"));
        String apiIndex = read(Path.of("docs/api/index.md"));
        String referenceIndex = read(Path.of("docs/reference/index.md"));
        String kotlinReference = read(Path.of("docs/reference/kotlin-api.md"));

        assertTrue(navigation.contains("Core Java API: api/java/core/index.html"));
        assertTrue(navigation.contains("Integrations Java API: api/java/integrations/index.html"));
        assertTrue(navigation.contains("Kotlin API: reference/kotlin-api.md"));
        assertTrue(apiIndex.contains("[Core Java API](java/core/index.html)"));
        assertTrue(apiIndex.contains("[Integrations Java API](java/integrations/index.html)"));
        assertTrue(apiIndex.contains("[Kotlin API](kotlin/index.html)"));
        assertTrue(referenceIndex.contains("exact Java signatures and Kotlin overloads"));
        assertTrue(kotlinReference.contains("[generated Kotlin API](../api/kotlin/index.html)"));
        assertFalse(kotlinReference.contains("The build verifies this"));
    }

    private static List<Path> markdownFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static List<Path> publicDocumentationSources() throws Exception {
        try (var paths = Files.walk(DOCS)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md")
                            || path.toString().endsWith(".java")
                            || path.toString().endsWith(".kt"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static boolean isExplicitPseudocode(String text, int fenceStart) {
        int marker = text.lastIndexOf(PSEUDOCODE_MARKER, fenceStart);
        return marker >= 0
                && text.substring(marker + PSEUDOCODE_MARKER.length(), fenceStart)
                        .isBlank();
    }

    private static boolean hasCanonicalExample(String text) {
        return CORE_EXAMPLE_FENCE.matcher(text).find()
                || SNIPPET_INCLUDE.matcher(text).find();
    }

    private static boolean hasCoreExampleMarker(String text, int fenceStart) {
        return CORE_EXAMPLE_MARKER_AT_END.matcher(text.substring(0, fenceStart)).find();
    }

    private static boolean isRetainedCallbackType(Class<?> type) {
        return type == PtyProvider.class || type.isAnnotationPresent(FunctionalInterface.class);
    }

    private static String publicTypeName(Class<?> type) {
        return type.getCanonicalName().substring("io.github.ulviar.procwright.".length());
    }

    private static void assertPoolExampleUsesTryWithResources(String source) {
        assertTrue(source.contains("try (Pooled"), "pool example must use try-with-resources");
        assertFalse(source.contains("awaitDrained"), "pool example must rely on synchronous close");
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
