/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class PublicIntegrationDocumentationCoverageTest {

    private static final Path PUBLIC_DOCS = Path.of("../docs");
    private static final Path TYPED_CONTENT_LENGTH_EXAMPLE = PUBLIC_DOCS.resolve(
            "examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java");
    private static final Path CORE_DOCUMENTATION_TEST =
            Path.of("../src/test/java/io/github/ulviar/procwright/examples/PublicDocumentationCoverageTest.java");

    @Test
    void publicIntegrationExampleReferencesResolveToCompileTestedMethods() throws Exception {
        Set<String> methodNames = integrationExampleMethods();

        for (String methodName : documentedIntegrationExampleReferences()) {
            assertTrue(
                    methodNames.contains(methodName),
                    () -> "public docs reference non-compiled integration example `" + methodName + "`");
        }
    }

    @Test
    void coreDocumentationTestOwnsPublicJavaSnippetCoverage() {
        assertTrue(
                Files.isRegularFile(CORE_DOCUMENTATION_TEST),
                "root module public documentation test owns Java snippet source-backed coverage");
    }

    @Test
    void typedContentLengthExampleAndContractStaySourceBacked() throws Exception {
        String source = Files.readString(TYPED_CONTENT_LENGTH_EXAMPLE, StandardCharsets.UTF_8);
        assertTrue(source.contains("ProtocolAdapters.typedJsonSession("));
        assertTrue(source.contains("ProtocolAdapters.contentLengthJsonSession(MAX_BODY_BYTES)"));
        assertTrue(source.contains("session.request(new TextMetricsRequest("));
        assertTrue(source.contains("\"Content-Length: \" + MAX_BODY_BYTES + \"\\r\\n\\r\\n\""));
        assertTrue(source.contains("Math.addExact(MAX_GENERATED_REQUEST_HEADER_BYTES, MAX_BODY_BYTES)"));
        assertTrue(source.contains(".withMaxRequestBytes(MAX_REQUEST_WIRE_BYTES)"));
        assertTrue(source.contains(".withMaxResponseBytes(MAX_RESPONSE_WIRE_BYTES)"));
        assertFalse(source.contains(".withMaxRequestChars("));
        assertFalse(source.contains(".withMaxResponseChars("));
        assertTrue(source.contains("catch (ProtocolSessionException failure)"));
        assertTrue(source.contains("TypedContentLengthJsonSessionExample.class.getName()"));

        String contract = Files.readString(PUBLIC_DOCS.resolve("scenarios/integrations.md"), StandardCharsets.UTF_8);
        assertTrue(contract.contains("Content-Length: N\\r\\n\\r\\n<body>"));
        assertTrue(contract.contains("at most 8192 bytes"));
        assertTrue(contract.contains("header block plus body"));
        assertTrue(contract.contains("The JSON body is always strict UTF-8"));
        assertTrue(contract.contains("`EOF` or `PROCESS_EXITED`, according to observation order"));
        assertFalse(contract.contains("`OVERSIZED_FRAME`, or `EOF`"));
        assertTrue(contract.contains("An admitted protocol request failure closes a direct session"));
    }

    private static Set<String> integrationExampleMethods() {
        return Arrays.stream(CommandBackedToolExamples.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> documentedIntegrationExampleReferences() throws Exception {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try (var paths = Files.walk(PUBLIC_DOCS)) {
            for (Path path :
                    paths.filter(path -> path.toString().endsWith(".md")).toList()) {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                var matcher = java.util.regex.Pattern.compile("CommandBackedToolExamples\\.([A-Za-z0-9_]+)")
                        .matcher(text);
                while (matcher.find()) {
                    String methodName = matcher.group(1);
                    if (!methodName.equals("java")) {
                        names.add(methodName);
                    }
                }
            }
        }
        return Set.copyOf(names);
    }
}
