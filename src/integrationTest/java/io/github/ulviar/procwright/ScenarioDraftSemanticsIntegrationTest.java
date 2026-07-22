/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.StreamChunk;
import io.github.ulviar.procwright.session.StreamSession;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ScenarioDraftSemanticsIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void configurationAndPoolingDoNotLaunchBeforeATerminalOperation() {
        AtomicInteger events = new AtomicInteger();
        AtomicInteger adapters = new AtomicInteger();
        CommandService service = Procwright.command(TestCliSupport.command());

        service.run().withArgs("exit").withDiagnosticListener(event -> events.incrementAndGet());
        service.interactive()
                .withArgs("controlled-line-repl")
                .withDiagnosticListener(event -> events.incrementAndGet());
        LineSessionScenario.Draft line = service.lineSession()
                .withArgs("controlled-line-repl")
                .withDiagnosticListener(event -> events.incrementAndGet());
        line.pooled().withMaxSize(2).withWarmupSize(1);
        service.listen().withArgs("exit").withDiagnosticListener(event -> events.incrementAndGet());
        ProtocolSessionScenario.Draft<String, String> protocol = service.protocolSession(() -> {
                    adapters.incrementAndGet();
                    return new FramedStringAdapter();
                })
                .withArgs("length-line-frame")
                .withDiagnosticListener(event -> events.incrementAndGet());
        protocol.pooled().withMaxSize(2).withWarmupSize(1);

        assertEquals(0, events.get());
        assertEquals(0, adapters.get());
    }

    @Test
    void runDraftCopiesCallerInputsAndBranchesWithoutMutation() {
        RunScenario.Draft base = Procwright.command(TestCliSupport.command()).run();

        String[] array = {"exit", "--stdout=array\n"};
        RunScenario.Draft fromArray = base.withArgs(array);
        array[1] = "--stdout=mutated\n";

        ArrayList<String> collection = new ArrayList<>(Arrays.asList("exit", "--stdout=collection\n"));
        RunScenario.Draft fromCollection = base.withArgs(collection);
        collection.set(1, "--stdout=mutated\n");

        RunScenario.Draft left = base.withArgs("exit", "--stdout=left\n");
        RunScenario.Draft right = base.withArgs("exit", "--stdout=right\n");

        assertEquals("array\n", fromArray.execute().stdout());
        assertEquals("collection\n", fromCollection.execute().stdout());
        assertEquals("left\n", left.execute().stdout());
        assertEquals("right\n", right.execute().stdout());
    }

    @Test
    void runDraftSupportsRepeatedAndConcurrentTerminalCalls() throws Exception {
        RunScenario.Draft draft = Procwright.command(TestCliSupport.command())
                .run()
                .withArgs("controlled-line-repl")
                .withInput("pid\n");

        long firstPid = responsePid(draft.execute().stdout());
        long secondPid = responsePid(draft.execute().stdout());
        assertNotEquals(firstPid, secondPid);

        List<CommandResult> concurrent = invokeConcurrently(draft::execute, draft::execute);
        assertNotEquals(
                responsePid(concurrent.get(0).stdout()),
                responsePid(concurrent.get(1).stdout()));
    }

    @Test
    void sessionAndStreamDraftsCopyArgumentsAndKeepBranchesIndependent() throws Exception {
        CommandService service = Procwright.command(TestCliSupport.command());

        String[] interactiveArguments = {"controlled-line-repl", "--response-prefix=array:"};
        InteractiveScenario.Draft interactiveFromArray = service.interactive().withArgs(interactiveArguments);
        interactiveArguments[1] = "--response-prefix=mutated:";
        assertEquals("array:value", interactiveResponse(interactiveFromArray, "value"));
        assertEquals(
                "left:value",
                interactiveResponse(
                        service.interactive().withArgs("controlled-line-repl", "--response-prefix=left:"), "value"));
        assertEquals(
                "right:value",
                interactiveResponse(
                        service.interactive().withArgs("controlled-line-repl", "--response-prefix=right:"), "value"));

        ArrayList<String> lineArguments =
                new ArrayList<>(List.of("controlled-line-repl", "--response-prefix=collection:"));
        LineSessionScenario.Draft lineFromCollection = service.lineSession().withArgs(lineArguments);
        lineArguments.set(1, "--response-prefix=mutated:");
        assertEquals("collection:value", lineResponse(lineFromCollection, "value"));
        assertEquals(
                "left:value",
                lineResponse(
                        service.lineSession().withArgs("controlled-line-repl", "--response-prefix=left:"), "value"));
        assertEquals(
                "right:value",
                lineResponse(
                        service.lineSession().withArgs("controlled-line-repl", "--response-prefix=right:"), "value"));

        String[] streamArguments = {"exit", "--stdout=stream-original"};
        StreamScenario.Draft streamFromArray = service.listen().withArgs(streamArguments);
        streamArguments[1] = "--stdout=mutated";
        assertEquals("stream-original", streamOutput(streamFromArray));
        assertEquals("left", streamOutput(service.listen().withArgs("exit", "--stdout=left")));
        assertEquals("right", streamOutput(service.listen().withArgs("exit", "--stdout=right")));

        String[] protocolArguments = {"length-line-frame"};
        ProtocolSessionScenario.Draft<String, String> protocolFromArray =
                service.protocolSession(FramedStringAdapter::new).withArgs(protocolArguments);
        protocolArguments[0] = "exit";
        try (ProtocolSession<String, String> session = protocolFromArray.open()) {
            assertEquals("copied", session.request("copied"));
        }
    }

    @Test
    void reusableSessionDraftsOpenIndependentProcessesConcurrently() throws Exception {
        CommandService service = Procwright.command(TestCliSupport.command());

        InteractiveScenario.Draft interactive = service.interactive().withArgs("controlled-line-repl");
        List<Long> interactivePids =
                invokeConcurrently(() -> interactivePid(interactive), () -> interactivePid(interactive));
        assertNotEquals(interactivePids.get(0), interactivePids.get(1));

        LineSessionScenario.Draft line = service.lineSession().withArgs("controlled-line-repl");
        List<Long> linePids = invokeConcurrently(() -> linePid(line), () -> linePid(line));
        assertNotEquals(linePids.get(0), linePids.get(1));

        AtomicInteger adapters = new AtomicInteger();
        ProtocolSessionScenario.Draft<String, String> protocol = service.protocolSession(() -> {
                    adapters.incrementAndGet();
                    return new ControlLineAdapter();
                })
                .withArgs("controlled-line-repl");
        List<Long> protocolPids = invokeConcurrently(() -> protocolPid(protocol), () -> protocolPid(protocol));
        assertNotEquals(protocolPids.get(0), protocolPids.get(1));
        assertEquals(2, adapters.get());
    }

    @Test
    void protocolFactoryCallsCanOverlapAcrossConcurrentDraftAndPoolOpens() throws Exception {
        OverlappingAdapterFactory sessionFactory = new OverlappingAdapterFactory();
        ProtocolSessionScenario.Draft<String, String> draft = Procwright.command(TestCliSupport.command())
                .protocolSession(sessionFactory)
                .withArgs("controlled-line-repl");

        List<Long> sessionPids = invokeConcurrently(() -> protocolPid(draft), () -> protocolPid(draft));

        assertNotEquals(sessionPids.get(0), sessionPids.get(1));
        assertEquals(2, sessionFactory.calls());
        assertEquals(2, sessionFactory.maximumConcurrentCalls());

        OverlappingAdapterFactory poolFactory = new OverlappingAdapterFactory();
        ProtocolSessionScenario.PoolDraft<String, String> poolDraft = Procwright.command(TestCliSupport.command())
                .protocolSession(poolFactory)
                .withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1);

        List<Long> poolPids =
                invokeConcurrently(() -> pooledProtocolPid(poolDraft), () -> pooledProtocolPid(poolDraft));

        assertNotEquals(poolPids.get(0), poolPids.get(1));
        assertEquals(2, poolFactory.calls());
        assertEquals(2, poolFactory.maximumConcurrentCalls());
    }

    @Test
    void reusableStreamDraftOpensOneIndependentProcessPerConcurrentTerminalCall() throws Exception {
        ConcurrentLinkedQueue<StreamChunk> chunks = new ConcurrentLinkedQueue<>();
        StreamScenario.Draft draft = Procwright.command(TestCliSupport.command())
                .listen()
                .withArgs("spawn-child", "--child-millis=100", "--linger-millis=100")
                .onOutput(chunks::add);

        List<Boolean> completions = invokeConcurrently(() -> awaitStream(draft), () -> awaitStream(draft));

        assertEquals(List.of(true, true), completions);
        String output = chunks.stream().map(StreamChunk::text).reduce("", String::concat);
        List<Long> childPids = output.lines()
                .filter(line -> line.startsWith("child:"))
                .map(line -> Long.parseLong(line.substring("child:".length()).trim()))
                .distinct()
                .toList();
        assertEquals(2, childPids.size(), output);
    }

    @Test
    void linePoolSnapshotsWorkerDraftAndCanBeOpenedRepeatedly() {
        LineSessionScenario.Draft base = Procwright.command(TestCliSupport.command())
                .lineSession()
                .withArgs("controlled-line-repl", "--response-prefix=snapshot:");
        LineSessionScenario.PoolDraft snapshot = base.pooled().withMaxSize(1).withWarmupSize(1);
        LineSessionScenario.PoolDraft changed = base.withArg("--response-prefix=changed:")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1);

        long firstPid;
        try (PooledLineSession pool = snapshot.open()) {
            assertEquals("snapshot:value", pool.request("value").text());
            firstPid = responsePid(pool.request("pid").text());
        }
        try (PooledLineSession pool = snapshot.open()) {
            assertEquals("snapshot:value", pool.request("value").text());
            assertNotEquals(firstPid, responsePid(pool.request("pid").text()));
        }
        try (PooledLineSession pool = changed.open()) {
            assertEquals("changed:value", pool.request("value").text());
        }
    }

    @Test
    void poolCrossFieldValidationIsIndependentOfSetterOrderForBothPoolTypes() {
        LineSessionScenario.Draft worker =
                Procwright.command(TestCliSupport.command()).lineSession().withArgs("controlled-line-repl");

        try (PooledLineSession first = worker.pooled()
                        .withWarmupSize(2)
                        .withMinIdle(2)
                        .withMaxSize(2)
                        .open();
                PooledLineSession second = worker.pooled()
                        .withMaxSize(2)
                        .withMinIdle(2)
                        .withWarmupSize(2)
                        .open()) {
            assertEquals(2, first.metrics().created());
            assertEquals(2, second.metrics().created());
        }

        assertInvalidLinePool(worker.pooled().withWarmupSize(2).withMaxSize(1));
        assertInvalidLinePool(worker.pooled().withMaxSize(1).withWarmupSize(2));
        assertInvalidLinePool(worker.pooled().withMinIdle(2).withMaxSize(1));
        assertInvalidLinePool(worker.pooled().withMaxSize(1).withMinIdle(2));

        ProtocolSessionScenario.Draft<String, String> protocolWorker = Procwright.command(TestCliSupport.command())
                .protocolSession(FramedStringAdapter::new)
                .withArgs("length-line-frame");
        try (PooledProtocolSession<String, String> first = protocolWorker
                        .pooled()
                        .withWarmupSize(2)
                        .withMinIdle(2)
                        .withMaxSize(2)
                        .open();
                PooledProtocolSession<String, String> second = protocolWorker
                        .pooled()
                        .withMaxSize(2)
                        .withMinIdle(2)
                        .withWarmupSize(2)
                        .open()) {
            assertEquals(2, first.metrics().created());
            assertEquals(2, second.metrics().created());
        }

        assertInvalidProtocolPool(protocolWorker.pooled().withWarmupSize(2).withMaxSize(1));
        assertInvalidProtocolPool(protocolWorker.pooled().withMaxSize(1).withWarmupSize(2));
        assertInvalidProtocolPool(protocolWorker.pooled().withMinIdle(2).withMaxSize(1));
        assertInvalidProtocolPool(protocolWorker.pooled().withMaxSize(1).withMinIdle(2));
    }

    @Test
    void protocolFactoryCreatesOneAdapterPerWorkerAndPerOpen() {
        AtomicInteger adapters = new AtomicInteger();
        ProtocolSessionScenario.Draft<String, String> reusable = Procwright.command(TestCliSupport.command())
                .protocolSession(() -> {
                    adapters.incrementAndGet();
                    return new FramedStringAdapter();
                })
                .withArgs("length-line-frame");

        assertEquals(0, adapters.get());
        try (ProtocolSession<String, String> first = reusable.open()) {
            assertEquals("first", first.request("first"));
        }
        try (ProtocolSession<String, String> second = reusable.open()) {
            assertEquals("second", second.request("second"));
        }
        assertEquals(2, adapters.get());

        ProtocolSessionScenario.PoolDraft<String, String> poolDraft =
                reusable.pooled().withMaxSize(2).withWarmupSize(2);
        assertEquals(2, adapters.get());
        try (PooledProtocolSession<String, String> firstPool = poolDraft.open()) {
            assertEquals(2, firstPool.metrics().created());
            assertEquals(4, adapters.get());
        }
        try (PooledProtocolSession<String, String> secondPool = poolDraft.open()) {
            assertEquals(2, secondPool.metrics().created());
            assertEquals(6, adapters.get());
        }
    }

    @Test
    void protocolPoolSnapshotsWorkerConfiguration() {
        ProtocolSessionScenario.Draft<String, String> base = Procwright.command(TestCliSupport.command())
                .protocolSession(ControlLineAdapter::new)
                .withArgs("controlled-line-repl", "--response-prefix=snapshot:");
        ProtocolSessionScenario.PoolDraft<String, String> snapshot =
                base.pooled().withMaxSize(1).withWarmupSize(1);
        ProtocolSessionScenario.PoolDraft<String, String> changed = base.withArg("--response-prefix=changed:")
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1);

        try (PooledProtocolSession<String, String> pool = snapshot.open()) {
            assertEquals("snapshot:value", pool.request("value"));
        }
        try (PooledProtocolSession<String, String> pool = changed.open()) {
            assertEquals("changed:value", pool.request("value"));
        }
    }

    @Test
    void protocolPoolCloseTimeoutKeepsCleanupObservableAndCancellationIsolated() throws Exception {
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        PooledProtocolSession<String, String> pool = Procwright.command(TestCliSupport.command())
                .protocolSession(ControlLineAdapter::new)
                .withArgs("controlled-line-repl")
                .pooled()
                .withWarmupSize(1)
                .withCloseTimeout(Duration.ofMillis(40))
                .withReset(worker -> {
                    resetEntered.countDown();
                    awaitIgnoringInterrupt(releaseReset);
                })
                .open();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> request = executor.submit(() -> pool.request("hello"));
            assertTrue(resetEntered.await(1, TimeUnit.SECONDS));

            PooledProtocolSessionException timeout = assertThrows(PooledProtocolSessionException.class, pool::close);
            assertEquals(PooledProtocolSessionException.Reason.DRAIN_TIMEOUT, timeout.reason());
            CompletableFuture<Void> cancelled = pool.closeAsync();
            CompletableFuture<Void> eventual = pool.closeAsync();
            assertTrue(cancelled.cancel(true));
            assertFalse(eventual.isCancelled());

            releaseReset.countDown();
            assertEquals("response:hello", request.get(1, TimeUnit.SECONDS));
            eventual.get(1, TimeUnit.SECONDS);
            pool.close();
            assertEquals(0, pool.metrics().size());
        } finally {
            releaseReset.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.close();
        }
    }

    @Test
    void protocolFactoryFailureOrNullDoesNotLaunchAProcess() {
        Path throwingPid = temporaryDirectory.resolve("throwing.pid");
        Path nullPid = temporaryDirectory.resolve("null.pid");
        AtomicInteger diagnosticEvents = new AtomicInteger();
        IllegalStateException factoryFailure = new IllegalStateException("factory failed");
        Supplier<ProtocolAdapter<String, String>> throwingFactory = () -> {
            throw factoryFailure;
        };
        Supplier<ProtocolAdapter<String, String>> nullFactory = () -> null;

        ProtocolSessionScenario.Draft<String, String> throwing = Procwright.command(TestCliSupport.command())
                .protocolSession(throwingFactory)
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArgs("spawn-child", "--pid-file=" + throwingPid, "--linger-millis=5000");
        ProtocolSessionScenario.Draft<String, String> returningNull = Procwright.command(TestCliSupport.command())
                .protocolSession(nullFactory)
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArgs("spawn-child", "--pid-file=" + nullPid, "--linger-millis=5000");

        assertSame(factoryFailure, assertThrows(IllegalStateException.class, throwing::open));
        NullPointerException directNull = assertThrows(NullPointerException.class, returningNull::open);
        assertEquals("adapterFactory returned null", directNull.getMessage());
        PooledProtocolSessionException throwingPoolFailure = assertThrows(
                PooledProtocolSessionException.class,
                () -> throwing.pooled().withWarmupSize(1).open());
        PooledProtocolSessionException nullPoolFailure = assertThrows(
                PooledProtocolSessionException.class,
                () -> returningNull.pooled().withWarmupSize(1).open());
        assertEquals(PooledProtocolSessionException.Reason.STARTUP_FAILED, throwingPoolFailure.reason());
        assertEquals(PooledProtocolSessionException.Reason.STARTUP_FAILED, nullPoolFailure.reason());
        assertSame(factoryFailure, throwingPoolFailure.getCause());
        assertEquals("adapterFactory returned null", nullPoolFailure.getCause().getMessage());
        assertEquals(0, diagnosticEvents.get());
        assertFalse(Files.exists(throwingPid));
        assertFalse(Files.exists(nullPid));
    }

    @Test
    void lineCharsetConfigurationUsesTheLastWrite() {
        LineSessionScenario.Draft base =
                Procwright.command(TestCliSupport.command()).lineSession().withArgs("controlled-line-repl");
        LineSessionScenario.Draft strict = base.withCharset(StandardCharsets.UTF_8)
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));
        LineSessionScenario.Draft lenient = base.withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .withCharset(StandardCharsets.UTF_8);

        try (LineSession session = strict.open()) {
            LineSessionException failure =
                    assertThrows(LineSessionException.class, () -> session.request("malformed-utf8"));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, failure.reason());
        }
        try (LineSession session = lenient.open()) {
            assertEquals("\uFFFD", session.request("malformed-utf8").text());
        }
    }

    @Test
    void protocolOverridesUseLastValueAndDraftCanPool() {
        ProtocolSessionScenario.Draft<byte[], String> base = Procwright.command(TestCliSupport.command())
                .protocolSession(FramedBytesAsLineAdapter::new)
                .withArgs("length-line-frame");
        ProtocolSessionScenario.Draft<byte[], String> strict = base.withCharsetPolicy(
                        CharsetPolicy.replace(StandardCharsets.UTF_8))
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));
        ProtocolSessionScenario.Draft<byte[], String> lenient = base.withCharsetPolicy(
                        CharsetPolicy.report(StandardCharsets.UTF_8))
                .withCharsetPolicy(CharsetPolicy.replace(StandardCharsets.UTF_8));

        try (ProtocolSession<byte[], String> session = strict.open()) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request(new byte[] {(byte) 0xFF}));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
        }
        try (ProtocolSession<byte[], String> session = lenient.open()) {
            assertEquals("\uFFFD\n", session.request(new byte[] {(byte) 0xFF}));
        }

        assertTrue(Arrays.stream(ProtocolSessionScenario.Draft.class.getMethods())
                .anyMatch(method -> method.getName().equals("pooled")));
    }

    @Test
    void expectDraftBranchesWithoutClaimingOutputBeforeOpen() throws Exception {
        try (Session session = Procwright.command(TestCliSupport.command())
                .interactive()
                .withArgs("controlled-line-repl", "--prompt=ready> ")
                .open()) {
            Expect.Draft base = session.expect().withTimeout(Duration.ofSeconds(1));
            Expect.Draft ansi = base.withAnsiControlSequenceStripping();

            assertNotSame(base, ansi);

            try (Expect expect = base.open()) {
                expect.expectText("ready> ");
            }
        }

        try (Session session = Procwright.command(TestCliSupport.command())
                .interactive()
                .withArgs("controlled-line-repl")
                .open()) {
            Expect.Draft draft = session.expect();
            session.sendLine("pid");
            BufferedReader stdout = new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8));
            assertTrue(stdout.readLine().startsWith("response:pid:"));
            assertThrows(IllegalStateException.class, draft::open);
        }
    }

    @Test
    void expectDraftReuseAndConcurrentOpenRespectSingleOutputOwnership() throws Exception {
        try (Session session = Procwright.command(TestCliSupport.command())
                .interactive()
                .withArgs("controlled-line-repl")
                .open()) {
            Expect.Draft draft = session.expect().withTimeout(Duration.ofSeconds(1));
            try (Expect expect = draft.open()) {
                assertNotNull(expect);
                assertThrows(IllegalStateException.class, draft::open);
            }
        }

        try (Session session = Procwright.command(TestCliSupport.command())
                .interactive()
                .withArgs("controlled-line-repl")
                .open()) {
            Expect.Draft draft = session.expect().withTimeout(Duration.ofSeconds(1));
            List<ExpectOpenAttempt> attempts =
                    invokeConcurrently(() -> attemptExpectOpen(draft), () -> attemptExpectOpen(draft));
            List<Expect> opened = attempts.stream()
                    .filter(attempt -> attempt.expect() != null)
                    .map(ExpectOpenAttempt::expect)
                    .toList();
            List<Throwable> failures = attempts.stream()
                    .filter(attempt -> attempt.failure() != null)
                    .map(ExpectOpenAttempt::failure)
                    .toList();
            try {
                assertEquals(1, opened.size());
                assertEquals(1, failures.size());
                assertInstanceOf(IllegalStateException.class, failures.get(0));
            } finally {
                opened.forEach(Expect::close);
            }
        }
    }

    private static String interactiveResponse(InteractiveScenario.Draft draft, String request) throws Exception {
        try (Session session = draft.open()) {
            BufferedReader stdout = new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8));
            session.sendLine(request);
            return stdout.readLine();
        }
    }

    private static String lineResponse(LineSessionScenario.Draft draft, String request) {
        try (LineSession session = draft.open()) {
            return session.request(request).text();
        }
    }

    private static String streamOutput(StreamScenario.Draft draft) throws Exception {
        StringBuilder output = new StringBuilder();
        try (StreamSession session =
                draft.onOutput(chunk -> output.append(chunk.text())).open()) {
            session.onExit().get(5, TimeUnit.SECONDS);
        }
        return output.toString();
    }

    private static long interactivePid(InteractiveScenario.Draft draft) throws Exception {
        return responsePid(interactiveResponse(draft, "pid"));
    }

    private static long linePid(LineSessionScenario.Draft draft) {
        try (LineSession session = draft.open()) {
            return responsePid(session.request("pid").text());
        }
    }

    private static long protocolPid(ProtocolSessionScenario.Draft<String, String> draft) {
        try (ProtocolSession<String, String> session = draft.open()) {
            return responsePid(session.request("pid"));
        }
    }

    private static long pooledProtocolPid(ProtocolSessionScenario.PoolDraft<String, String> draft) {
        try (PooledProtocolSession<String, String> pool = draft.open()) {
            return responsePid(pool.request("pid"));
        }
    }

    private static boolean awaitStream(StreamScenario.Draft draft) throws Exception {
        try (StreamSession session = draft.open()) {
            return session.onExit().get(5, TimeUnit.SECONDS).exitCode().orElse(-1) == 0;
        }
    }

    private static long responsePid(String response) {
        String marker = "response:pid:";
        int markerIndex = response.indexOf(marker);
        if (markerIndex < 0) {
            throw new AssertionError("Missing PID response: " + response);
        }
        int start = markerIndex + marker.length();
        int end = response.indexOf('\n', start);
        return Long.parseLong(
                response.substring(start, end < 0 ? response.length() : end).trim());
    }

    private static void assertInvalidLinePool(LineSessionScenario.PoolDraft draft) {
        assertThrows(IllegalArgumentException.class, draft::open);
    }

    private static void assertInvalidProtocolPool(ProtocolSessionScenario.PoolDraft<String, String> draft) {
        assertThrows(IllegalArgumentException.class, draft::open);
    }

    private static ExpectOpenAttempt attemptExpectOpen(Expect.Draft draft) {
        try {
            return new ExpectOpenAttempt(draft.open(), null);
        } catch (Throwable failure) {
            return new ExpectOpenAttempt(null, failure);
        }
    }

    private static <T> List<T> invokeConcurrently(Callable<T> firstCall, Callable<T> secondCall) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> first = executor.submit(awaitStart(firstCall, ready, start));
            Future<T> second = executor.submit(awaitStart(secondCall, ready, start));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of(get(first), get(second));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static <T> Callable<T> awaitStart(Callable<T> call, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent terminal start was not released");
            }
            return call.call();
        };
    }

    private static <T> T get(Future<T> future) throws Exception {
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private record ExpectOpenAttempt(Expect expect, Throwable failure) {}

    private static final class OverlappingAdapterFactory implements Supplier<ProtocolAdapter<String, String>> {
        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger maximumConcurrentCalls = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch bothInsideFactory = new CountDownLatch(2);

        @Override
        public ProtocolAdapter<String, String> get() {
            calls.incrementAndGet();
            int active = activeCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(active, Math::max);
            bothInsideFactory.countDown();
            try {
                assertTrue(
                        bothInsideFactory.await(2, TimeUnit.SECONDS),
                        "Procwright serialized concurrent adapter factory calls");
                return new ControlLineAdapter();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while proving concurrent factory calls", exception);
            } finally {
                activeCalls.decrementAndGet();
            }
        }

        private int calls() {
            return calls.get();
        }

        private int maximumConcurrentCalls() {
            return maximumConcurrentCalls.get();
        }
    }

    private static final class FramedStringAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            byte[] body = request.getBytes(StandardCharsets.UTF_8);
            writer.writeLine(Integer.toString(body.length));
            writer.write(body);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = parseLength(stdout.readLine(32));
            byte[] body = stdout.readExactly(length);
            assertEquals("", stdout.readLine(1));
            assertEquals("END", stdout.readLine(8));
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class ControlLineAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(256);
        }
    }

    private static final class FramedBytesAsLineAdapter implements ProtocolAdapter<byte[], String> {

        @Override
        public void writeRequest(byte[] request, ProtocolWriter writer) {
            writer.writeLine(Integer.toString(request.length));
            writer.write(Arrays.copyOf(request, request.length));
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            parseLength(stdout.readLine(32));
            String body = stdout.readTextUntil((byte) '\n', 32);
            assertEquals("END", stdout.readLine(8));
            return body;
        }
    }

    private static int parseLength(String line) {
        if (!line.startsWith("len:")) {
            throw new IllegalArgumentException("missing length prefix");
        }
        return Integer.parseInt(line.substring("len:".length()));
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
