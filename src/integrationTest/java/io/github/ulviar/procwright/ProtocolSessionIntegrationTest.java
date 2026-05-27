package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.PooledProtocolSessionOptions;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.session.SessionOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ProtocolSessionIntegrationTest {

    @Test
    void protocolSessionSupportsMultilineStringRequests() {
        try (ProtocolSession<String, String> session =
                fixtureService().protocolSession(new FramedStringAdapter(), call -> call.args("length-line-frame"))) {
            String response = session.request("one\ntwo", Duration.ofSeconds(2));

            assertEquals("one\ntwo", response);
        }
    }

    @Test
    void readinessProbeRunsBeforeProtocolSessionIsReturned() {
        try (ProtocolSession<String, String> session = fixtureService()
                .protocolSession(new FramedStringAdapter(), call -> call.args("length-line-frame")
                        .readiness(ready -> assertEquals("ready", ready.request("ready")))
                        .readinessTimeout(Duration.ofSeconds(2)))) {
            assertEquals("payload", session.request("payload"));
        }
    }

    @Test
    void requestSizeLimitIsTypedFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new FramedStringAdapter(), call -> call.args("length-line-frame")
                        .maxRequestBytes(4))
                .request("too-large"));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
    }

    @Test
    void requestCharacterLimitIsTypedFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new TextLineAdapter(), call -> call.args("controlled-line-repl")
                        .maxRequestChars(4))
                .request("hello"));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
    }

    @Test
    void responseSizeLimitIsTypedFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new FramedStringAdapter(), call -> call.args("length-line-frame")
                        .maxResponseBytes(8))
                .request("response is too large"));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void strictCharsetPolicyReportsMalformedOutput() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new FramedBytesAsLineAdapter(), call -> call.args("length-line-frame")
                        .charsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8)))
                .request(new byte[] {(byte) 0xFF}));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
        assertEquals(true, exception.transcript().malformed());
    }

    @Test
    void textCharacterLimitFailsBeforeDelimiterOrEof() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(
                        new StdoutLineAdapter(4), call -> call.args("burst", "--stdout-bytes=100", "--stdout-byte=a"))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void textCharacterLimitAppliesAcrossMultipleTextReads() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new TwoLineTextAdapter(), call -> call.args("controlled-line-repl")
                        .maxResponseChars(20))
                .request("multi"));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void outputBacklogOverflowIsVisibleWhenAdapterReadsOtherStream() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new StdoutLineAdapter(16), call -> call.args(
                                "partial", "--stdout=", "--stderr=" + "e".repeat(4096), "--hold-millis=5000")
                        .outputBacklogLimit(128))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
    }

    @Test
    void delimiterReadStopsAtResponseByteLimitAndKeepsTranscriptBounded() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new DelimiterBytesAdapter(), call -> call.args(
                                "burst", "--stdout-bytes=256k", "--stdout-byte=a")
                        .maxResponseBytes(4096)
                        .transcriptLimit(128))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertTrue(exception.transcript().truncated());
        assertTrue(exception.transcript().text().length() <= 128);
    }

    @Test
    void decoderFailureIsTypedProtocolFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new FailingDecoderAdapter(), call -> call.args("exit", "--stdout=ignored"))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
    }

    @Test
    void processExitBeforeResponseIsTypedProtocolFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(new StdoutLineAdapter(16), call -> call.args("exit"))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, exception.reason());
    }

    @Test
    void protocolRequestTimeoutIsTypedFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> fixtureService()
                .protocolSession(
                        new StdoutLineAdapter(16),
                        call -> call.args("ignore-stdin", "--millis=5000", "--started=false"))
                .request("", Duration.ofMillis(100)));

        assertEquals(ProtocolSessionException.Reason.TIMEOUT, exception.reason());
    }

    @Test
    void pooledProtocolSessionReusesTypedWorkersWithoutExposingLease() {
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .pooledProtocol(FramedStringAdapter::new, call -> call.args("length-line-frame")
                        .maxSize(2)
                        .warmupSize(1)
                        .minIdle(1)
                        .readiness(ready -> assertEquals("ready", ready.request("ready"))))) {
            assertEquals("first", pool.request("first"));
            assertEquals("second", pool.request("second"));

            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.created());
            assertEquals(2, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
            assertEquals(1, metrics.idle());
        }
    }

    @Test
    void pooledProtocolSessionRecordsRetireReason() {
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .pooledProtocol(
                        FramedStringAdapter::new,
                        call -> call.args("length-line-frame").maxSize(1).maxRequestsPerWorker(1))) {
            assertEquals("first", pool.request("first"));

            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
        }
    }

    @Test
    void serviceLevelPooledProtocolDefaultsAreAppliedToScenarioInvocations() throws Exception {
        CommandService service = fixtureService(
                ProtocolSessionOptions.defaults(),
                PooledProtocolSessionOptions.defaults()
                        .withMaxSize(1)
                        .withWarmupSize(1)
                        .withMinIdle(1)
                        .withMaxRequestsPerWorker(1));

        try (PooledProtocolSession<String, String> pool =
                service.pooledProtocol(FramedStringAdapter::new, call -> call.args("length-line-frame"))) {
            assertEquals("first", pool.request("first"));

            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
            assertEquals(true, awaitIdle(pool, 1));
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.size());
            assertEquals(1, metrics.idle());
            assertEquals(2, metrics.created());
        }
    }

    @Test
    void pooledProtocolWarmupReadinessFailureIsStartupFailure() {
        PooledProtocolSessionException exception =
                assertThrows(PooledProtocolSessionException.class, () -> fixtureService()
                        .pooledProtocol(FramedStringAdapter::new, call -> call.args("length-line-frame")
                                .warmupSize(1)
                                .readiness(ready -> {
                                    throw new IllegalStateException("not ready");
                                })));

        assertEquals(PooledProtocolSessionException.Reason.STARTUP_FAILED, exception.reason());
    }

    @Test
    void pooledProtocolAcquireTimeoutIsDistinctFromRequestTimeout() throws Exception {
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .pooledProtocol(SleepingAdapter::new, call -> call.args("ignore-stdin", "--millis=5000")
                        .maxSize(1)
                        .acquireTimeout(Duration.ofMillis(100)))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            CountDownLatch firstStarted = new CountDownLatch(1);
            try {
                Future<String> first = executor.submit(() -> {
                    firstStarted.countDown();
                    return pool.request("first", Duration.ofSeconds(2));
                });
                assertEquals(true, firstStarted.await(1, TimeUnit.SECONDS));
                assertEquals(true, awaitLeased(pool, 1));

                PooledProtocolSessionException exception =
                        assertThrows(PooledProtocolSessionException.class, () -> pool.request("second"));

                assertEquals(PooledProtocolSessionException.Reason.ACQUIRE_TIMEOUT, exception.reason());
                assertEquals("slept:first", first.get());
            } finally {
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pooledProtocolCreatesOneAdapterPerWorker() throws Exception {
        ConcurrentLinkedQueue<Integer> adapterIds = new ConcurrentLinkedQueue<>();
        AtomicIntegerAdapterFactory factory = new AtomicIntegerAdapterFactory(adapterIds);

        try (PooledProtocolSession<String, String> pool = fixtureService()
                .pooledProtocol(
                        factory::newAdapter,
                        call -> call.args("length-line-frame").maxSize(2).warmupSize(2))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<String> first = executor.submit(() -> pool.request("first"));
                Future<String> second = executor.submit(() -> pool.request("second"));

                assertEquals(true, first.get().matches("adapter-[12]:first"));
                assertEquals(true, second.get().matches("adapter-[12]:second"));
                assertEquals(2, adapterIds.size());
            } finally {
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pooledProtocolSerializesAdapterFactoryCalls() throws Exception {
        SerializedAdapterFactory factory = new SerializedAdapterFactory();

        try (PooledProtocolSession<String, String> pool = fixtureService()
                .pooledProtocol(factory::newAdapter, call -> call.args("length-line-frame")
                        .maxSize(2))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<String> first = executor.submit(() -> pool.request("first"));
                Future<String> second = executor.submit(() -> pool.request("second"));

                assertEquals(true, first.get().matches("adapter-[12]:first"));
                assertEquals(true, second.get().matches("adapter-[12]:second"));
                assertEquals(2, factory.created());
            } finally {
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolPoolHealthHookTimeoutIsBounded() {
        PooledProtocolSessionException exception = assertThrows(PooledProtocolSessionException.class, () -> {
            try (PooledProtocolSession<String, String> pool = fixtureService()
                    .pooledProtocol(FramedStringAdapter::new, call -> call.args("length-line-frame")
                            .maxSize(1)
                            .warmupSize(1)
                            .hookTimeout(Duration.ofMillis(50))
                            .healthCheck(worker -> {
                                sleepIgnoringInterrupt(Duration.ofSeconds(5));
                                return true;
                            }))) {
                pool.request("hello");
            }
        });

        assertEquals(PooledProtocolSessionException.Reason.HOOK_TIMEOUT, exception.reason());
    }

    @Test
    void protocolPoolResetHookTimeoutIsBoundedAndRetiresWorker() {
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .pooledProtocol(FramedStringAdapter::new, call -> call.args("length-line-frame")
                        .maxSize(1)
                        .hookTimeout(Duration.ofMillis(50))
                        .reset(worker -> sleepIgnoringInterrupt(Duration.ofSeconds(5))))) {
            PooledProtocolSessionException exception =
                    assertThrows(PooledProtocolSessionException.class, () -> pool.request("hello"));

            assertEquals(PooledProtocolSessionException.Reason.HOOK_TIMEOUT, exception.reason());
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.TIMEOUT));
        }
    }

    private static CommandService fixtureService() {
        return fixtureService(ProtocolSessionOptions.defaults(), PooledProtocolSessionOptions.defaults());
    }

    private static CommandService fixtureService(
            ProtocolSessionOptions protocolOptions, PooledProtocolSessionOptions pooledProtocolOptions) {
        return new CommandService(
                TestCliSupport.command(),
                RunOptions.defaults(),
                SessionOptions.defaults(),
                io.github.ulviar.procwright.session.LineSessionOptions.defaults(),
                io.github.ulviar.procwright.session.StreamOptions.defaults(),
                io.github.ulviar.procwright.session.PooledLineSessionOptions.defaults(),
                protocolOptions,
                pooledProtocolOptions);
    }

    private static boolean awaitIdle(PooledProtocolSession<?, ?> pool, int expectedIdle) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.metrics().idle() == expectedIdle) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static boolean awaitLeased(PooledProtocolSession<?, ?> pool, int expectedLeased)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.metrics().leased() == expectedLeased) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
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

    private static final class TextLineAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.write(request);
            writer.write("\n");
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(64);
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

    private static final class StdoutLineAdapter implements ProtocolAdapter<String, String> {

        private final int maxChars;

        private StdoutLineAdapter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(maxChars);
        }
    }

    private static final class TwoLineTextAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            return stdout.readLine(32) + "\n" + stdout.readLine(32);
        }
    }

    private static final class FailingDecoderAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            throw new IllegalArgumentException("bad response");
        }
    }

    private static final class DelimiterBytesAdapter implements ProtocolAdapter<String, byte[]> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public byte[] readResponse(ProtocolReaders readers) {
            return readers.stdout().readUntil((byte) '\n', 512 * 1024);
        }
    }

    private static final class SleepingAdapter implements ProtocolAdapter<String, String> {

        private String request;

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            this.request = request;
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            }
            return "slept:" + request;
        }
    }

    private static final class AtomicIntegerAdapterFactory {

        private final ConcurrentLinkedQueue<Integer> adapterIds;
        private int nextId;

        private AtomicIntegerAdapterFactory(ConcurrentLinkedQueue<Integer> adapterIds) {
            this.adapterIds = adapterIds;
        }

        private ProtocolAdapter<String, String> newAdapter() {
            int id = ++nextId;
            adapterIds.add(id);
            return new WorkerScopedAdapter(id);
        }
    }

    private static final class SerializedAdapterFactory {

        private final AtomicBoolean inFactory = new AtomicBoolean();
        private int nextId;

        private ProtocolAdapter<String, String> newAdapter() {
            if (!inFactory.compareAndSet(false, true)) {
                throw new IllegalStateException("factory used concurrently");
            }
            try {
                sleepIgnoringInterrupt(Duration.ofMillis(100));
                return new WorkerScopedAdapter(++nextId);
            } finally {
                inFactory.set(false);
            }
        }

        private int created() {
            return nextId;
        }
    }

    private static final class WorkerScopedAdapter implements ProtocolAdapter<String, String> {

        private final int id;
        private String request;

        private WorkerScopedAdapter(int id) {
            this.id = id;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            this.request = request;
            byte[] body = request.getBytes(StandardCharsets.UTF_8);
            writer.writeLine(Integer.toString(body.length));
            writer.write(body);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = parseLength(stdout.readLine(32));
            stdout.readExactly(length);
            assertEquals("", stdout.readLine(1));
            assertEquals("END", stdout.readLine(8));
            return "adapter-" + id + ":" + request;
        }
    }

    private static void sleepIgnoringInterrupt(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static int parseLength(String line) {
        if (!line.startsWith("len:")) {
            throw new IllegalArgumentException("missing length prefix");
        }
        return Integer.parseInt(line.substring("len:".length()));
    }
}
