/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.StreamSession;
import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PublicApiSurfaceTest {

    private static final Set<String> PUBLIC_API_PACKAGES = Set.of(
            "io.github.ulviar.procwright",
            "io.github.ulviar.procwright.command",
            "io.github.ulviar.procwright.diagnostics",
            "io.github.ulviar.procwright.session",
            "io.github.ulviar.procwright.terminal");

    private static final Set<String> PUBLIC_API_TYPES = Set.of(
            "io.github.ulviar.procwright.CommandService",
            "io.github.ulviar.procwright.Procwright",
            "io.github.ulviar.procwright.ProcwrightException",
            "io.github.ulviar.procwright.InteractiveScenario",
            "io.github.ulviar.procwright.InteractiveScenario$Draft",
            "io.github.ulviar.procwright.LineSessionScenario",
            "io.github.ulviar.procwright.LineSessionScenario$Draft",
            "io.github.ulviar.procwright.LineSessionScenario$PoolDraft",
            "io.github.ulviar.procwright.ProtocolSessionScenario",
            "io.github.ulviar.procwright.ProtocolSessionScenario$Draft",
            "io.github.ulviar.procwright.ProtocolSessionScenario$PoolDraft",
            "io.github.ulviar.procwright.RunScenario",
            "io.github.ulviar.procwright.RunScenario$Draft",
            "io.github.ulviar.procwright.StreamScenario",
            "io.github.ulviar.procwright.StreamScenario$Draft",
            "io.github.ulviar.procwright.command.CapturePolicy",
            "io.github.ulviar.procwright.command.CapturePolicy$Bounded",
            "io.github.ulviar.procwright.command.CapturePolicy$Discard",
            "io.github.ulviar.procwright.command.CapturePolicy$ToPath",
            "io.github.ulviar.procwright.command.CharsetPolicy",
            "io.github.ulviar.procwright.command.CommandException",
            "io.github.ulviar.procwright.command.CommandExecutionException",
            "io.github.ulviar.procwright.command.CommandExecutionException$Reason",
            "io.github.ulviar.procwright.command.CommandInput",
            "io.github.ulviar.procwright.command.CommandResult",
            "io.github.ulviar.procwright.command.CommandSpec",
            "io.github.ulviar.procwright.command.EnvironmentPolicy",
            "io.github.ulviar.procwright.command.OutputMode",
            "io.github.ulviar.procwright.command.ShutdownPolicy",
            "io.github.ulviar.procwright.diagnostics.CommandEcho",
            "io.github.ulviar.procwright.diagnostics.DiagnosticEvent",
            "io.github.ulviar.procwright.diagnostics.DiagnosticEventType",
            "io.github.ulviar.procwright.diagnostics.DiagnosticListener",
            "io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink",
            "io.github.ulviar.procwright.session.Expect",
            "io.github.ulviar.procwright.session.Expect$Draft",
            "io.github.ulviar.procwright.session.ExpectException",
            "io.github.ulviar.procwright.session.ExpectException$Reason",
            "io.github.ulviar.procwright.session.ExpectMatch",
            "io.github.ulviar.procwright.session.ExpectTranscriptValues",
            "io.github.ulviar.procwright.session.LineResponse",
            "io.github.ulviar.procwright.session.LineSession",
            "io.github.ulviar.procwright.session.LineSessionException",
            "io.github.ulviar.procwright.session.LineSessionException$Reason",
            "io.github.ulviar.procwright.session.LineTranscript",
            "io.github.ulviar.procwright.session.PooledLineSession",
            "io.github.ulviar.procwright.session.PooledLineSessionException",
            "io.github.ulviar.procwright.session.PooledLineSessionException$Reason",
            "io.github.ulviar.procwright.session.PooledLineSessionMetrics",
            "io.github.ulviar.procwright.session.PooledProtocolSession",
            "io.github.ulviar.procwright.session.PooledProtocolSessionException",
            "io.github.ulviar.procwright.session.PooledProtocolSessionException$Reason",
            "io.github.ulviar.procwright.session.PooledProtocolSessionMetrics",
            "io.github.ulviar.procwright.session.PooledWorkerRetireReason",
            "io.github.ulviar.procwright.session.ProtocolAdapter",
            "io.github.ulviar.procwright.session.ProtocolReader",
            "io.github.ulviar.procwright.session.ProtocolReaders",
            "io.github.ulviar.procwright.session.ProtocolSession",
            "io.github.ulviar.procwright.session.ProtocolSessionException",
            "io.github.ulviar.procwright.session.ProtocolSessionException$Reason",
            "io.github.ulviar.procwright.session.ProtocolTranscript",
            "io.github.ulviar.procwright.session.ProtocolWriter",
            "io.github.ulviar.procwright.session.ResponseDecoder",
            "io.github.ulviar.procwright.session.ResponseDecoder$Reader",
            "io.github.ulviar.procwright.session.Session",
            "io.github.ulviar.procwright.session.SessionExit",
            "io.github.ulviar.procwright.session.StreamChunk",
            "io.github.ulviar.procwright.session.StreamException",
            "io.github.ulviar.procwright.session.StreamException$Reason",
            "io.github.ulviar.procwright.session.StreamExit",
            "io.github.ulviar.procwright.session.StreamListener",
            "io.github.ulviar.procwright.session.StreamSession",
            "io.github.ulviar.procwright.session.StreamSource",
            "io.github.ulviar.procwright.session.StreamTranscript",
            "io.github.ulviar.procwright.terminal.PtyProvider",
            "io.github.ulviar.procwright.terminal.PtyRequest",
            "io.github.ulviar.procwright.terminal.TerminalPolicy",
            "io.github.ulviar.procwright.terminal.TerminalSignal",
            "io.github.ulviar.procwright.terminal.TerminalSize");

    private static final String MODULE_NAME = "io.github.ulviar.procwright";

    @Test
    void corePublicTopLevelTypesStayInApprovedPackages() throws Exception {
        assertEquals(PUBLIC_API_PACKAGES, publicTopLevelPackages(CommandService.class));
    }

    @Test
    void corePublicApiTypesStayInApprovedBaseline() throws Exception {
        assertEquals(PUBLIC_API_TYPES, publicApiTypeNames(CommandService.class));
    }

    @Test
    void corePublicSignaturesExposeOnlyJdkAndPublicApiTypes() throws Exception {
        for (Class<?> type : publicTopLevelTypes(CommandService.class)) {
            Type genericSuperclass = type.getGenericSuperclass();
            if (genericSuperclass != null) {
                assertAllowedType(genericSuperclass, type.getName() + " superclass", seenTypes());
            }
            assertAllowedTypes(type.getGenericInterfaces(), type.getName() + " interfaces");
            assertAllowedTypes(type.getTypeParameters(), type.getName() + " type parameters");
            Class<?>[] permittedSubclasses = type.getPermittedSubclasses();
            if (permittedSubclasses != null) {
                for (Class<?> permittedSubclass : permittedSubclasses) {
                    assertAllowedPermittedSubclass(permittedSubclass, type.getName() + " permitted subclasses");
                }
            }
            for (Constructor<?> constructor : type.getConstructors()) {
                assertAllowedTypes(constructor.getTypeParameters(), type.getName() + " constructor type parameters");
                assertAllowedTypes(constructor.getGenericParameterTypes(), type.getName() + " constructor parameters");
                assertAllowedTypes(constructor.getGenericExceptionTypes(), type.getName() + " constructor exceptions");
            }
            for (Method method : type.getMethods()) {
                assertAllowedTypes(
                        method.getTypeParameters(), type.getName() + "#" + method.getName() + " type parameters");
                assertAllowedType(
                        method.getGenericReturnType(),
                        type.getName() + "#" + method.getName() + " return",
                        seenTypes());
                assertAllowedTypes(
                        method.getGenericParameterTypes(), type.getName() + "#" + method.getName() + " parameters");
                assertAllowedTypes(
                        method.getGenericExceptionTypes(), type.getName() + "#" + method.getName() + " exceptions");
            }
            for (Field field : type.getFields()) {
                assertAllowedType(field.getGenericType(), type.getName() + "#" + field.getName(), seenTypes());
            }
        }
    }

    @Test
    void moduleDescriptorExportsOnlyPublicApiPackages() throws Exception {
        ModuleDescriptor descriptor = moduleDescriptor(CommandService.class);

        assertEquals(MODULE_NAME, descriptor.name());
        assertEquals(
                PUBLIC_API_PACKAGES,
                descriptor.exports().stream()
                        .map(export -> export.source())
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
    }

    @Test
    void expectConstructionHasOnlyTheSessionEntryPoint() {
        assertEquals(
                Set.of(),
                Stream.of(Expect.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .map(Method::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void procwrightAndCommandServiceExposeOnlyTheApprovedEntryPoints() {
        assertEquals(Set.of("command"), declaredPublicMethodNames(Procwright.class));
        assertEquals(
                Set.of("run", "interactive", "lineSession", "listen", "protocolSession"),
                declaredPublicMethodNames(CommandService.class));
    }

    @Test
    void entryPointTypesExposeExactKindsModifiersOwnersAndNoPublicState() throws Exception {
        for (Class<?> type : Set.of(
                Procwright.class,
                CommandService.class,
                RunScenario.class,
                InteractiveScenario.class,
                LineSessionScenario.class,
                StreamScenario.class,
                ProtocolSessionScenario.class)) {
            assertExactTypeShape(type, Modifier.PUBLIC | Modifier.FINAL, false, false);
            assertNoPublicConstructorsOrFields(type);
        }

        for (Class<?> namespace : Set.of(
                RunScenario.class,
                InteractiveScenario.class,
                LineSessionScenario.class,
                StreamScenario.class,
                ProtocolSessionScenario.class)) {
            assertEquals(Set.of(), declaredPublicMethodNames(namespace), namespace.getName());
        }

        assertDraftOwner(RunScenario.Draft.class, RunScenario.class);
        assertDraftOwner(InteractiveScenario.Draft.class, InteractiveScenario.class);
        assertDraftOwner(LineSessionScenario.Draft.class, LineSessionScenario.class);
        assertDraftOwner(LineSessionScenario.PoolDraft.class, LineSessionScenario.class);
        assertDraftOwner(StreamScenario.Draft.class, StreamScenario.class);
        assertDraftOwner(ProtocolSessionScenario.Draft.class, ProtocolSessionScenario.class);
        assertDraftOwner(ProtocolSessionScenario.PoolDraft.class, ProtocolSessionScenario.class);
        assertDraftOwner(Expect.Draft.class, Expect.class);

        assertExactTypeShape(Session.class, Modifier.PUBLIC | Modifier.ABSTRACT | Modifier.INTERFACE, true, true);
        assertNoPublicConstructorsOrFields(Session.class);
        Method expect = Session.class.getDeclaredMethod("expect");
        assertEquals(Session.class, expect.getDeclaringClass());
        assertEquals(Expect.Draft.class, expect.getReturnType());
        assertTrue(expect.isDefault());
        assertFalse(Modifier.isStatic(expect.getModifiers()));
    }

    @Test
    void entryPointsExposeExactPublicSignatures() {
        assertDeclaredPublicSignatures(
                Procwright.class,
                Set.of(
                        "public static CommandService command(java.lang.String)",
                        "public static CommandService command(command.CommandSpec)"));
        assertDeclaredPublicSignatures(
                CommandService.class,
                Set.of(
                        "public RunScenario.Draft run()",
                        "public InteractiveScenario.Draft interactive()",
                        "public LineSessionScenario.Draft lineSession()",
                        "public StreamScenario.Draft listen()",
                        "public <I, O> ProtocolSessionScenario.Draft<I, O> protocolSession(java.util.function.Supplier<? extends session.ProtocolAdapter<I, O>>)"));
        assertDeclaredPublicSignatures(
                Session.class, Set.of("expect"), Set.of("public default session.Expect.Draft expect()"));
        assertDeclaredPublicSignatures(
                StreamSession.class,
                Set.of(
                        "public abstract void close()",
                        "public abstract session.StreamTranscript diagnostics()",
                        "public abstract java.util.concurrent.CompletableFuture<session.StreamExit> onExit()"));
    }

    @Test
    void scenarioNamespacesExposeOnlyNestedWriteOnlyDrafts() {
        assertEquals(Set.of("Draft"), publicNestedTypeNames(RunScenario.class));
        assertEquals(Set.of("Draft"), publicNestedTypeNames(InteractiveScenario.class));
        assertEquals(Set.of("Draft", "PoolDraft"), publicNestedTypeNames(LineSessionScenario.class));
        assertEquals(Set.of("Draft"), publicNestedTypeNames(StreamScenario.class));
        assertEquals(Set.of("Draft", "PoolDraft"), publicNestedTypeNames(ProtocolSessionScenario.class));

        for (Class<?> draft : Set.of(
                RunScenario.Draft.class,
                InteractiveScenario.Draft.class,
                LineSessionScenario.Draft.class,
                LineSessionScenario.PoolDraft.class,
                StreamScenario.Draft.class,
                ProtocolSessionScenario.Draft.class,
                ProtocolSessionScenario.PoolDraft.class,
                Expect.Draft.class)) {
            assertTrue(draft.isInterface(), draft.getName());
            assertEquals(
                    Set.of(),
                    Stream.of(draft.getMethods())
                            .map(Method::getName)
                            .filter(name -> name.equals("build")
                                    || name.equals("configuredBy")
                                    || name.equals("withOptions")
                                    || name.endsWith("Options"))
                            .collect(java.util.stream.Collectors.toSet()),
                    draft.getName());
        }

        assertEquals(
                Set.of(
                        "withArg",
                        "withArgs",
                        "withWorkingDirectory",
                        "withEnvironment",
                        "withInheritedEnvironment",
                        "withCleanEnvironment",
                        "withCapture",
                        "withShutdown",
                        "withTimeout",
                        "withCharset",
                        "withCharsetPolicy",
                        "withOutput",
                        "withInput",
                        "withDiagnosticListener",
                        "withDiagnosticTranscriptSink",
                        "execute"),
                publicMethodNames(RunScenario.Draft.class));
        assertEquals(
                Set.of(
                        "withArg",
                        "withArgs",
                        "withWorkingDirectory",
                        "withEnvironment",
                        "withInheritedEnvironment",
                        "withCleanEnvironment",
                        "withShutdown",
                        "withIdleTimeout",
                        "withCharset",
                        "withTerminal",
                        "withPtyProvider",
                        "withTerminalSize",
                        "withReadiness",
                        "withReadinessTimeout",
                        "withDiagnosticListener",
                        "withDiagnosticTranscriptSink",
                        "open"),
                publicMethodNames(InteractiveScenario.Draft.class));
        assertEquals(
                Set.of(
                        "withArg",
                        "withArgs",
                        "withWorkingDirectory",
                        "withEnvironment",
                        "withInheritedEnvironment",
                        "withCleanEnvironment",
                        "withShutdown",
                        "withIdleTimeout",
                        "withTerminal",
                        "withPtyProvider",
                        "withTerminalSize",
                        "withReadiness",
                        "withReadinessTimeout",
                        "withRequestTimeout",
                        "withTranscriptLimit",
                        "withStdoutBacklogLines",
                        "withStdoutBacklogChars",
                        "withMaxLineChars",
                        "withMaxRequestBytes",
                        "withMaxRequestChars",
                        "withMaxResponseLines",
                        "withMaxResponseChars",
                        "withCharset",
                        "withCharsetPolicy",
                        "withResponseDecoder",
                        "withDiagnosticListener",
                        "withDiagnosticTranscriptSink",
                        "pooled",
                        "open"),
                publicMethodNames(LineSessionScenario.Draft.class));
        assertEquals(poolDraftMethodNames(), publicMethodNames(LineSessionScenario.PoolDraft.class));
        assertEquals(
                Set.of(
                        "withArg",
                        "withArgs",
                        "withWorkingDirectory",
                        "withEnvironment",
                        "withInheritedEnvironment",
                        "withCleanEnvironment",
                        "withShutdown",
                        "withTimeout",
                        "withCharset",
                        "withDiagnosticLimit",
                        "onOutput",
                        "withDiagnosticListener",
                        "withDiagnosticTranscriptSink",
                        "open"),
                publicMethodNames(StreamScenario.Draft.class));

        Set<String> protocolDraftMethods = Set.of(
                "withArg",
                "withArgs",
                "withWorkingDirectory",
                "withEnvironment",
                "withInheritedEnvironment",
                "withCleanEnvironment",
                "withShutdown",
                "withIdleTimeout",
                "withTerminal",
                "withPtyProvider",
                "withTerminalSize",
                "withReadiness",
                "withReadinessTimeout",
                "withRequestTimeout",
                "withTranscriptLimit",
                "withOutputBacklogLimit",
                "withMaxRequestBytes",
                "withMaxRequestChars",
                "withMaxResponseBytes",
                "withMaxResponseChars",
                "withCharset",
                "withCharsetPolicy",
                "withDiagnosticListener",
                "withDiagnosticTranscriptSink",
                "pooled",
                "open");
        assertEquals(protocolDraftMethods, publicMethodNames(ProtocolSessionScenario.Draft.class));
        assertEquals(poolDraftMethodNames(), publicMethodNames(ProtocolSessionScenario.PoolDraft.class));
        assertEquals(
                Set.of(
                        "withTimeout",
                        "withTranscriptLimit",
                        "withMatchBufferLimit",
                        "withCharset",
                        "withAnsiControlSequenceStripping",
                        "withTranscriptValues",
                        "open"),
                publicMethodNames(Expect.Draft.class));
    }

    @Test
    void draftInterfacesExposeExactGenericSignaturesAndOverloads() {
        assertPublicSignatures(
                RunScenario.Draft.class,
                withLaunchSignatures(
                        "RunScenario.Draft",
                        "RunScenario.Draft withCapture(command.CapturePolicy)",
                        "RunScenario.Draft withShutdown(command.ShutdownPolicy)",
                        "RunScenario.Draft withTimeout(java.time.Duration)",
                        "RunScenario.Draft withCharset(java.nio.charset.Charset)",
                        "RunScenario.Draft withCharsetPolicy(command.CharsetPolicy)",
                        "RunScenario.Draft withOutput(command.OutputMode)",
                        "RunScenario.Draft withInput(java.lang.String)",
                        "RunScenario.Draft withInput(java.lang.String, java.nio.charset.Charset)",
                        "RunScenario.Draft withInput(command.CommandInput)",
                        "RunScenario.Draft withDiagnosticListener(diagnostics.DiagnosticListener)",
                        "RunScenario.Draft withDiagnosticTranscriptSink(diagnostics.DiagnosticTranscriptSink)",
                        "command.CommandResult execute()"));
        assertPublicSignatures(
                InteractiveScenario.Draft.class,
                withLaunchSignatures(
                        "InteractiveScenario.Draft",
                        "InteractiveScenario.Draft withShutdown(command.ShutdownPolicy)",
                        "InteractiveScenario.Draft withIdleTimeout(java.time.Duration)",
                        "InteractiveScenario.Draft withCharset(java.nio.charset.Charset)",
                        "InteractiveScenario.Draft withTerminal(terminal.TerminalPolicy)",
                        "InteractiveScenario.Draft withPtyProvider(terminal.PtyProvider)",
                        "InteractiveScenario.Draft withTerminalSize(terminal.TerminalSize)",
                        "InteractiveScenario.Draft withReadiness(java.util.function.Consumer<session.Session>)",
                        "InteractiveScenario.Draft withReadinessTimeout(java.time.Duration)",
                        "InteractiveScenario.Draft withDiagnosticListener(diagnostics.DiagnosticListener)",
                        "InteractiveScenario.Draft withDiagnosticTranscriptSink(diagnostics.DiagnosticTranscriptSink)",
                        "session.Session open()"));
        assertPublicSignatures(
                LineSessionScenario.Draft.class,
                withLaunchSignatures(
                        "LineSessionScenario.Draft",
                        "LineSessionScenario.Draft withShutdown(command.ShutdownPolicy)",
                        "LineSessionScenario.Draft withIdleTimeout(java.time.Duration)",
                        "LineSessionScenario.Draft withTerminal(terminal.TerminalPolicy)",
                        "LineSessionScenario.Draft withPtyProvider(terminal.PtyProvider)",
                        "LineSessionScenario.Draft withTerminalSize(terminal.TerminalSize)",
                        "LineSessionScenario.Draft withReadiness(java.util.function.Consumer<session.LineSession>)",
                        "LineSessionScenario.Draft withReadinessTimeout(java.time.Duration)",
                        "LineSessionScenario.Draft withRequestTimeout(java.time.Duration)",
                        "LineSessionScenario.Draft withTranscriptLimit(int)",
                        "LineSessionScenario.Draft withStdoutBacklogLines(int)",
                        "LineSessionScenario.Draft withStdoutBacklogChars(int)",
                        "LineSessionScenario.Draft withMaxLineChars(int)",
                        "LineSessionScenario.Draft withMaxRequestBytes(int)",
                        "LineSessionScenario.Draft withMaxRequestChars(int)",
                        "LineSessionScenario.Draft withMaxResponseLines(int)",
                        "LineSessionScenario.Draft withMaxResponseChars(int)",
                        "LineSessionScenario.Draft withCharset(java.nio.charset.Charset)",
                        "LineSessionScenario.Draft withCharsetPolicy(command.CharsetPolicy)",
                        "LineSessionScenario.Draft withResponseDecoder(session.ResponseDecoder)",
                        "LineSessionScenario.Draft withDiagnosticListener(diagnostics.DiagnosticListener)",
                        "LineSessionScenario.Draft withDiagnosticTranscriptSink(diagnostics.DiagnosticTranscriptSink)",
                        "LineSessionScenario.PoolDraft pooled()",
                        "session.LineSession open()"));
        assertPublicSignatures(
                StreamScenario.Draft.class,
                withLaunchSignatures(
                        "StreamScenario.Draft",
                        "StreamScenario.Draft withShutdown(command.ShutdownPolicy)",
                        "StreamScenario.Draft withTimeout(java.time.Duration)",
                        "StreamScenario.Draft withCharset(java.nio.charset.Charset)",
                        "StreamScenario.Draft withDiagnosticLimit(int)",
                        "StreamScenario.Draft onOutput(session.StreamListener)",
                        "StreamScenario.Draft withDiagnosticListener(diagnostics.DiagnosticListener)",
                        "StreamScenario.Draft withDiagnosticTranscriptSink(diagnostics.DiagnosticTranscriptSink)",
                        "session.StreamSession open()"));
        assertPublicSignatures(
                ProtocolSessionScenario.Draft.class,
                withLaunchSignatures(
                        "ProtocolSessionScenario.Draft<I, O>",
                        "ProtocolSessionScenario.Draft<I, O> withShutdown(command.ShutdownPolicy)",
                        "ProtocolSessionScenario.Draft<I, O> withIdleTimeout(java.time.Duration)",
                        "ProtocolSessionScenario.Draft<I, O> withTerminal(terminal.TerminalPolicy)",
                        "ProtocolSessionScenario.Draft<I, O> withPtyProvider(terminal.PtyProvider)",
                        "ProtocolSessionScenario.Draft<I, O> withTerminalSize(terminal.TerminalSize)",
                        "ProtocolSessionScenario.Draft<I, O> withReadiness(java.util.function.Consumer<session.ProtocolSession<I, O>>)",
                        "ProtocolSessionScenario.Draft<I, O> withReadinessTimeout(java.time.Duration)",
                        "ProtocolSessionScenario.Draft<I, O> withRequestTimeout(java.time.Duration)",
                        "ProtocolSessionScenario.Draft<I, O> withTranscriptLimit(int)",
                        "ProtocolSessionScenario.Draft<I, O> withOutputBacklogLimit(int)",
                        "ProtocolSessionScenario.Draft<I, O> withMaxRequestBytes(int)",
                        "ProtocolSessionScenario.Draft<I, O> withMaxRequestChars(int)",
                        "ProtocolSessionScenario.Draft<I, O> withMaxResponseBytes(int)",
                        "ProtocolSessionScenario.Draft<I, O> withMaxResponseChars(int)",
                        "ProtocolSessionScenario.Draft<I, O> withCharset(java.nio.charset.Charset)",
                        "ProtocolSessionScenario.Draft<I, O> withCharsetPolicy(command.CharsetPolicy)",
                        "ProtocolSessionScenario.Draft<I, O> withDiagnosticListener(diagnostics.DiagnosticListener)",
                        "ProtocolSessionScenario.Draft<I, O> withDiagnosticTranscriptSink(diagnostics.DiagnosticTranscriptSink)",
                        "ProtocolSessionScenario.PoolDraft<I, O> pooled()",
                        "session.ProtocolSession<I, O> open()"));
        assertPublicSignatures(
                LineSessionScenario.PoolDraft.class,
                poolSignatures("LineSessionScenario.PoolDraft", "session.LineSession", "session.PooledLineSession"));
        assertPublicSignatures(
                ProtocolSessionScenario.PoolDraft.class,
                poolSignatures(
                        "ProtocolSessionScenario.PoolDraft<I, O>",
                        "session.ProtocolSession<I, O>",
                        "session.PooledProtocolSession<I, O>"));
        assertPublicSignatures(
                Expect.Draft.class,
                Set.of(
                        "session.Expect.Draft withTimeout(java.time.Duration)",
                        "session.Expect.Draft withTranscriptLimit(int)",
                        "session.Expect.Draft withMatchBufferLimit(int)",
                        "session.Expect.Draft withCharset(java.nio.charset.Charset)",
                        "session.Expect.Draft withAnsiControlSequenceStripping()",
                        "session.Expect.Draft withTranscriptValues(session.ExpectTranscriptValues)",
                        "session.Expect open()"));
    }

    @Test
    void protocolReaderExposesExactBoundedReadOperations() {
        assertPublicSignatures(
                ProtocolReader.class,
                Set.of(
                        "byte readByte()",
                        "byte[] readExactly(int)",
                        "byte[] readUntil(byte, int)",
                        "int read(byte[], int, int)",
                        "java.lang.String readLine(int)",
                        "java.lang.String readTextExactly(int, int)",
                        "java.lang.String readTextUntil(byte, int)"));
    }

    @Test
    void obsoleteProtocolConfigurationTypesAreAbsentFromClasspath() {
        ClassLoader classLoader = CommandService.class.getClassLoader();

        for (String type : Set.of(
                "io.github.ulviar.procwright.PooledLineSessionScenario",
                "io.github.ulviar.procwright.PooledProtocolSessionScenario",
                "io.github.ulviar.procwright.ProtocolWorkerConfiguration",
                "io.github.ulviar.procwright.ReusableProtocolSessionScenario",
                "io.github.ulviar.procwright.command.CommandInvocation",
                "io.github.ulviar.procwright.command.RunOptions",
                "io.github.ulviar.procwright.diagnostics.DiagnosticsOptions",
                "io.github.ulviar.procwright.session.ExpectOutputFilter",
                "io.github.ulviar.procwright.session.ExpectOptions",
                "io.github.ulviar.procwright.session.LineSessionInvocation",
                "io.github.ulviar.procwright.session.LineSessionOptions",
                "io.github.ulviar.procwright.session.PooledLineSessionInvocation",
                "io.github.ulviar.procwright.session.PooledLineSessionOptions",
                "io.github.ulviar.procwright.session.PooledProtocolSessionInvocation",
                "io.github.ulviar.procwright.session.PooledProtocolSessionOptions",
                "io.github.ulviar.procwright.session.ProtocolSessionInvocation",
                "io.github.ulviar.procwright.session.ProtocolSessionOptions",
                "io.github.ulviar.procwright.session.SessionInvocation",
                "io.github.ulviar.procwright.session.SessionOptions",
                "io.github.ulviar.procwright.session.StreamInvocation",
                "io.github.ulviar.procwright.session.StreamOptions",
                "io.github.ulviar.procwright.session.StreamStdinPolicy")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type, false, classLoader), type);
        }
    }

    private static Set<String> declaredPublicMethodNames(Class<?> type) {
        return Stream.of(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void assertDraftOwner(Class<?> draft, Class<?> owner) {
        assertEquals(owner, draft.getDeclaringClass(), draft.getName());
        assertExactTypeShape(
                draft, Modifier.PUBLIC | Modifier.STATIC | Modifier.ABSTRACT | Modifier.INTERFACE, true, false);
        assertNoPublicConstructorsOrFields(draft);
    }

    private static void assertExactTypeShape(
            Class<?> type, int expectedModifiers, boolean interfaceType, boolean sealed) {
        assertEquals(
                expectedModifiers,
                type.getModifiers(),
                () -> type.getName() + " modifiers: " + Modifier.toString(type.getModifiers()));
        assertEquals(interfaceType, type.isInterface(), type.getName());
        assertEquals(sealed, type.isSealed(), type.getName());
        assertFalse(type.isAnnotation(), type.getName());
        assertFalse(type.isEnum(), type.getName());
        assertFalse(type.isRecord(), type.getName());
    }

    private static void assertNoPublicConstructorsOrFields(Class<?> type) {
        assertEquals(
                Set.of(),
                Stream.of(type.getConstructors())
                        .map(Constructor::toString)
                        .collect(java.util.stream.Collectors.toSet()),
                type.getName());
        assertEquals(
                Set.of(),
                Stream.of(type.getFields()).map(Field::toString).collect(java.util.stream.Collectors.toSet()),
                type.getName());
    }

    private static Set<String> publicMethodNames(Class<?> type) {
        return Stream.of(type.getMethods()).map(Method::getName).collect(java.util.stream.Collectors.toSet());
    }

    private static void assertPublicSignatures(Class<?> type, Set<String> expected) {
        Set<String> canonicalExpected = expected.stream()
                .map(signature -> "public abstract " + signature)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> actual = Stream.of(type.getMethods())
                .filter(method -> !method.isBridge() && !method.isSynthetic())
                .map(PublicApiSurfaceTest::publicSignature)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertEquals(canonicalExpected, actual, type.getName());
    }

    private static void assertDeclaredPublicSignatures(Class<?> type, Set<String> expected) {
        assertDeclaredPublicSignatures(type, null, expected);
    }

    private static void assertDeclaredPublicSignatures(
            Class<?> type, Set<String> selectedMethodNames, Set<String> expected) {
        assertEquals(
                expected,
                Stream.of(type.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> selectedMethodNames == null || selectedMethodNames.contains(method.getName()))
                        .filter(method -> !method.isBridge() && !method.isSynthetic())
                        .map(PublicApiSurfaceTest::publicSignature)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                type.getName());
    }

    private static Set<String> withLaunchSignatures(String draftType, String... scenarioSignatures) {
        TreeSet<String> signatures = new TreeSet<>(Arrays.asList(scenarioSignatures));
        signatures.add(draftType + " withArg(java.lang.String)");
        signatures.add(draftType + " withArgs(java.lang.String...)");
        signatures.add(draftType + " withArgs(java.util.Collection<java.lang.String>)");
        signatures.add(draftType + " withWorkingDirectory(java.nio.file.Path)");
        signatures.add(draftType + " withEnvironment(java.lang.String, java.lang.String)");
        signatures.add(draftType + " withInheritedEnvironment()");
        signatures.add(draftType + " withCleanEnvironment()");
        return signatures;
    }

    private static Set<String> poolSignatures(String draftType, String workerType, String resultType) {
        return Set.of(
                draftType + " withMaxSize(int)",
                draftType + " withWarmupSize(int)",
                draftType + " withMinIdle(int)",
                draftType + " withAcquireTimeout(java.time.Duration)",
                draftType + " withHookTimeout(java.time.Duration)",
                draftType + " withCloseTimeout(java.time.Duration)",
                draftType + " withMaxRequestsPerWorker(int)",
                draftType + " withMaxWorkerAge(java.time.Duration)",
                draftType + " withBackgroundReplenishment(boolean)",
                draftType + " withReset(java.util.function.Consumer<" + workerType + ">)",
                draftType + " withHealthCheck(java.util.function.Predicate<" + workerType + ">)",
                resultType + " open()");
    }

    private static String publicSignature(Method method) {
        Type[] parameters = method.getGenericParameterTypes();
        String parameterList = java.util.stream.IntStream.range(0, parameters.length)
                .mapToObj(index -> parameterTypeName(method, parameters[index], index))
                .collect(java.util.stream.Collectors.joining(", "));
        String exceptions = Stream.of(method.getGenericExceptionTypes())
                .map(PublicApiSurfaceTest::publicTypeName)
                .collect(java.util.stream.Collectors.joining(", "));
        return methodModifiers(method)
                + methodTypeParameters(method)
                + publicTypeName(method.getGenericReturnType())
                + " "
                + method.getName()
                + "("
                + parameterList
                + ")"
                + (exceptions.isEmpty() ? "" : " throws " + exceptions);
    }

    private static String methodModifiers(Method method) {
        int modifiers = method.getModifiers();
        StringBuilder result = new StringBuilder();
        if (Modifier.isPublic(modifiers)) {
            result.append("public ");
        } else if (Modifier.isProtected(modifiers)) {
            result.append("protected ");
        } else if (Modifier.isPrivate(modifiers)) {
            result.append("private ");
        }
        if (Modifier.isStatic(modifiers)) {
            result.append("static ");
        }
        if (Modifier.isFinal(modifiers)) {
            result.append("final ");
        }
        if (method.isDefault()) {
            result.append("default ");
        } else if (Modifier.isAbstract(modifiers)) {
            result.append("abstract ");
        }
        if (Modifier.isSynchronized(modifiers)) {
            result.append("synchronized ");
        }
        if (Modifier.isNative(modifiers)) {
            result.append("native ");
        }
        if (Modifier.isStrict(modifiers)) {
            result.append("strictfp ");
        }
        return result.toString();
    }

    private static String methodTypeParameters(Method method) {
        if (method.getTypeParameters().length == 0) {
            return "";
        }
        return Stream.of(method.getTypeParameters())
                        .map(PublicApiSurfaceTest::typeParameterDeclaration)
                        .collect(java.util.stream.Collectors.joining(", ", "<", ">"))
                + " ";
    }

    private static String typeParameterDeclaration(TypeVariable<Method> variable) {
        String bounds = Stream.of(variable.getBounds())
                .filter(bound -> bound != Object.class)
                .map(PublicApiSurfaceTest::publicTypeName)
                .collect(java.util.stream.Collectors.joining(" & "));
        return variable.getName() + (bounds.isEmpty() ? "" : " extends " + bounds);
    }

    private static String parameterTypeName(Method method, Type type, int index) {
        String name = publicTypeName(type);
        if (method.isVarArgs() && index == method.getParameterCount() - 1) {
            return name.substring(0, name.length() - 2) + "...";
        }
        return name;
    }

    private static String publicTypeName(Type type) {
        return type.getTypeName().replace("io.github.ulviar.procwright.", "").replace('$', '.');
    }

    private static Set<String> poolDraftMethodNames() {
        return Set.of(
                "withMaxSize",
                "withWarmupSize",
                "withMinIdle",
                "withAcquireTimeout",
                "withHookTimeout",
                "withCloseTimeout",
                "withMaxRequestsPerWorker",
                "withMaxWorkerAge",
                "withBackgroundReplenishment",
                "withReset",
                "withHealthCheck",
                "open");
    }

    private static Set<String> publicNestedTypeNames(Class<?> type) {
        return Stream.of(type.getDeclaredClasses())
                .filter(nested -> Modifier.isPublic(nested.getModifiers()))
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> publicTopLevelPackages(Class<?> anchor) throws Exception {
        TreeSet<String> packages = new TreeSet<>();
        for (Class<?> type : publicTopLevelTypes(anchor)) {
            packages.add(type.getPackageName());
        }
        return packages;
    }

    private static Set<String> publicApiTypeNames(Class<?> anchor) throws Exception {
        TreeSet<String> typeNames = new TreeSet<>();
        for (Class<?> type : publicTopLevelTypes(anchor)) {
            typeNames.add(type.getName());
        }
        return typeNames;
    }

    private static Set<Class<?>> publicTopLevelTypes(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        TreeSet<Class<?>> types = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
        if (Files.isRegularFile(classesRoot)) {
            types.addAll(publicTopLevelTypesFromJar(anchor, classesRoot));
        } else {
            try (Stream<Path> files = Files.walk(classesRoot)) {
                for (Path classFile :
                        files.filter(PublicApiSurfaceTest::isTopLevelClass).toList()) {
                    Class<?> type = Class.forName(className(classesRoot, classFile), false, anchor.getClassLoader());
                    if (Modifier.isPublic(type.getModifiers()) && isPublicApiType(type)) {
                        types.add(type);
                    }
                }
            }
        }
        ArrayDeque<Class<?>> queue = new ArrayDeque<>(types);
        while (!queue.isEmpty()) {
            Class<?> current = queue.removeFirst();
            for (Class<?> nested : current.getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers()) && types.add(nested)) {
                    queue.addLast(nested);
                }
            }
        }
        return types;
    }

    private static Set<Class<?>> publicTopLevelTypesFromJar(Class<?> anchor, Path jarPath) throws Exception {
        TreeSet<Class<?>> types = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry :
                    jar.stream().filter(PublicApiSurfaceTest::isTopLevelClass).toList()) {
                Class<?> type = Class.forName(className(entry), false, anchor.getClassLoader());
                if (Modifier.isPublic(type.getModifiers()) && isPublicApiType(type)) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    private static ModuleDescriptor moduleDescriptor(Class<?> anchor) throws Exception {
        Path classesRoot = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(classesRoot)) {
            try (JarFile jar = new JarFile(classesRoot.toFile())) {
                JarEntry entry = jar.getJarEntry("module-info.class");
                assertTrue(entry != null, "Core artifact must contain module-info.class");
                try (var input = jar.getInputStream(entry)) {
                    return ModuleDescriptor.read(input);
                }
            }
        }
        Path moduleInfo = classesRoot.resolve("module-info.class");
        assertTrue(Files.isRegularFile(moduleInfo), "Core classes must contain module-info.class");
        try (var input = Files.newInputStream(moduleInfo)) {
            return ModuleDescriptor.read(input);
        }
    }

    private static void assertAllowedTypes(Type[] types, String location) {
        Set<Type> seen = seenTypes();
        for (Type type : types) {
            assertAllowedType(type, location, seen);
        }
    }

    private static void assertAllowedType(Type type, String location, Set<Type> seen) {
        if (!seen.add(type)) {
            return;
        }
        if (type instanceof Class<?> classType) {
            assertAllowedClass(classType, location);
        } else if (type instanceof ParameterizedType parameterizedType) {
            assertAllowedType(parameterizedType.getRawType(), location, seen);
            Type ownerType = parameterizedType.getOwnerType();
            if (ownerType != null) {
                assertAllowedType(ownerType, location, seen);
            }
            assertAllowedTypes(parameterizedType.getActualTypeArguments(), location, seen);
        } else if (type instanceof GenericArrayType arrayType) {
            assertAllowedType(arrayType.getGenericComponentType(), location, seen);
        } else if (type instanceof TypeVariable<?> variable) {
            assertAllowedTypes(variable.getBounds(), location, seen);
        } else if (type instanceof WildcardType wildcard) {
            assertAllowedTypes(wildcard.getLowerBounds(), location, seen);
            assertAllowedTypes(wildcard.getUpperBounds(), location, seen);
        } else {
            throw new AssertionError("Unsupported reflection type at " + location + ": " + type);
        }
    }

    private static void assertAllowedTypes(Type[] types, String location, Set<Type> seen) {
        for (Type type : types) {
            assertAllowedType(type, location, seen);
        }
    }

    private static Set<Type> seenTypes() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void assertAllowedClass(Class<?> type, String location) {
        if (type.isPrimitive() || type == Void.TYPE) {
            return;
        }
        if (type.isArray()) {
            assertAllowedClass(type.componentType(), location);
            return;
        }
        String packageName = type.getPackageName();
        assertTrue(
                packageName.startsWith("java.") || (PUBLIC_API_PACKAGES.contains(packageName) && isPublicApiType(type)),
                () -> "Public core API leaks " + type.getName() + " at " + location);
    }

    private static void assertAllowedPermittedSubclass(Class<?> type, String location) {
        String packageName = type.getPackageName();
        if (packageName.startsWith("io.github.ulviar.procwright.internal.")) {
            return;
        }
        assertAllowedClass(type, location);
    }

    private static boolean isPublicApiType(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("io.github.ulviar.procwright.internal.")) {
            return false;
        }
        return true;
    }

    private static boolean isTopLevelClass(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".class")
                && !name.contains("$")
                && !"module-info.class".equals(name)
                && !"package-info.class".equals(name);
    }

    private static boolean isTopLevelClass(JarEntry entry) {
        String name = entry.getName();
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return !entry.isDirectory()
                && name.endsWith(".class")
                && !fileName.contains("$")
                && !"module-info.class".equals(fileName)
                && !"package-info.class".equals(fileName);
    }

    private static String className(Path classesRoot, Path classFile) {
        String relativeName = classesRoot.relativize(classFile).toString();
        String simpleName = relativeName.substring(0, relativeName.length() - ".class".length());
        return simpleName.replace(File.separatorChar, '.');
    }

    private static String className(JarEntry entry) {
        String entryName = entry.getName();
        return entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
    }
}
