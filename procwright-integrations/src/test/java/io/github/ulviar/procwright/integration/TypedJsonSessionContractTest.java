/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class TypedJsonSessionContractTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final CommandSpec MISSING_COMMAND = CommandSpec.of("procwright-transport-factory-before-launch");

    @Test
    void transportFactoryRuntimeFailurePropagatesBeforeSessionLaunch() {
        IllegalStateException failure = new IllegalStateException("transport runtime");
        var factory = typedFactory(TextNode::valueOf, JsonNode::textValue, () -> {
            throw failure;
        });

        IllegalStateException propagated =
                assertThrows(IllegalStateException.class, () -> Procwright.command(MISSING_COMMAND)
                        .protocolSession(factory)
                        .open());

        assertSame(failure, propagated);
    }

    @Test
    void transportFactoryErrorPropagatesBeforeSessionLaunch() {
        AssertionError failure = new AssertionError("transport error");
        var factory = typedFactory(TextNode::valueOf, JsonNode::textValue, () -> {
            throw failure;
        });

        AssertionError propagated = assertThrows(AssertionError.class, () -> Procwright.command(MISSING_COMMAND)
                .protocolSession(factory)
                .open());

        assertSame(failure, propagated);
    }

    @Test
    void nullTransportFailsBeforeSessionLaunch() {
        var factory = typedFactory(TextNode::valueOf, JsonNode::textValue, () -> null);

        NullPointerException failure =
                assertThrows(NullPointerException.class, () -> Procwright.command(MISSING_COMMAND)
                        .protocolSession(factory)
                        .open());

        assertEquals("transportFactory returned null", failure.getMessage());
    }

    @Test
    void encodeRuntimeFailureMapsToProtocolFailureWithCauseIdentity() {
        IllegalArgumentException callbackFailure = new IllegalArgumentException("encode runtime");

        ProtocolSessionException failure = requestFailure(
                ignored -> {
                    throw callbackFailure;
                },
                JsonNode::textValue);

        assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
        assertSame(callbackFailure, failure.getCause());
    }

    @Test
    void encodeErrorPropagatesWithIdentity() {
        AssertionError callbackFailure = new AssertionError("encode error");

        AssertionError propagated = assertThrows(
                AssertionError.class,
                () -> request(
                        ignored -> {
                            throw callbackFailure;
                        },
                        JsonNode::textValue));

        assertSame(callbackFailure, propagated);
    }

    @Test
    void encodeProtocolSessionExceptionRetainsTypedReasonAndIdentity() {
        ProtocolSessionException callbackFailure = protocolFailure(ProtocolSessionException.Reason.CLOSED);

        ProtocolSessionException propagated = assertThrows(
                ProtocolSessionException.class,
                () -> request(
                        ignored -> {
                            throw callbackFailure;
                        },
                        JsonNode::textValue));

        assertSame(callbackFailure, propagated);
        assertEquals(ProtocolSessionException.Reason.CLOSED, propagated.reason());
    }

    @Test
    void nullEncodeResultMapsToProtocolFailure() {
        ProtocolSessionException failure = requestFailure(ignored -> null, JsonNode::textValue);

        assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
        NullPointerException cause = assertInstanceOf(NullPointerException.class, failure.getCause());
        assertEquals("encode returned null", cause.getMessage());
    }

    @Test
    void decodeRuntimeFailureMapsToDecoderFailureWithCauseIdentity() {
        IllegalArgumentException callbackFailure = new IllegalArgumentException("decode runtime");

        ProtocolSessionException failure = requestFailure(TextNode::valueOf, ignored -> {
            throw callbackFailure;
        });

        assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, failure.reason());
        assertSame(callbackFailure, failure.getCause());
    }

    @Test
    void decodeErrorPropagatesWithIdentity() {
        AssertionError callbackFailure = new AssertionError("decode error");

        AssertionError propagated = assertThrows(
                AssertionError.class,
                () -> request(TextNode::valueOf, ignored -> {
                    throw callbackFailure;
                }));

        assertSame(callbackFailure, propagated);
    }

    @Test
    void decodeProtocolSessionExceptionRetainsTypedReasonAndIdentity() {
        ProtocolSessionException callbackFailure = protocolFailure(ProtocolSessionException.Reason.EOF);

        ProtocolSessionException propagated = assertThrows(
                ProtocolSessionException.class,
                () -> request(TextNode::valueOf, ignored -> {
                    throw callbackFailure;
                }));

        assertSame(callbackFailure, propagated);
        assertEquals(ProtocolSessionException.Reason.EOF, propagated.reason());
    }

    @Test
    void nullDecodeResultMapsToDecoderFailure() {
        ProtocolSessionException failure = requestFailure(TextNode::valueOf, ignored -> null);

        assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, failure.reason());
        NullPointerException cause = assertInstanceOf(NullPointerException.class, failure.getCause());
        assertEquals("decode returned null", cause.getMessage());
    }

    @Test
    void sharedCallbacksOverlapAcrossFreshAdaptersOnRealPoolWorkers() throws Exception {
        CountDownLatch encodeEntered = new CountDownLatch(2);
        CountDownLatch decodeEntered = new CountDownLatch(2);
        Set<ProtocolAdapter<JsonNode, JsonNode>> transports =
                Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        Function<String, JsonNode> encode = value -> {
            awaitOverlap(encodeEntered);
            return TextNode.valueOf(value);
        };
        Function<JsonNode, String> decode = value -> {
            awaitOverlap(decodeEntered);
            return value.textValue();
        };
        Supplier<ProtocolAdapter<String, String>> factory = typedFactory(encode, decode, () -> {
            ProtocolAdapter<JsonNode, JsonNode> transport =
                    ProtocolAdapters.jsonLinesSession(1024).get();
            transports.add(transport);
            return transport;
        });
        PooledProtocolSession<String, String> pool = Procwright.command(
                        ProtocolAdaptersTestWorker.command("json-lines"))
                .protocolSession(factory)
                .withRequestTimeout(REQUEST_TIMEOUT)
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(2)
                .open();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> requestAfter(start, pool, "first"));
            Future<String> second = executor.submit(() -> requestAfter(start, pool, "second"));

            start.countDown();

            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
            assertEquals(2, transports.size());
            assertEquals(0, encodeEntered.getCount());
            assertEquals(0, decodeEntered.getCount());
        } finally {
            executor.shutdownNow();
            pool.close();
        }
    }

    @Test
    void retainedTransportFactoryCanOverlapAcrossConcurrentFactoryCalls() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(2);
        Supplier<ProtocolAdapter<String, String>> factory = typedFactory(TextNode::valueOf, JsonNode::textValue, () -> {
            awaitOverlap(factoryEntered);
            return ProtocolAdapters.jsonLinesSession(1024).get();
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ProtocolAdapter<String, String>> first = executor.submit(() -> factoryAfter(start, factory));
            Future<ProtocolAdapter<String, String>> second = executor.submit(() -> factoryAfter(start, factory));

            start.countDown();

            assertNotSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(0, factoryEntered.getCount());
        } finally {
            executor.shutdownNow();
        }
    }

    private static ProtocolSessionException requestFailure(
            Function<String, JsonNode> encode, Function<JsonNode, String> decode) {
        return assertThrows(ProtocolSessionException.class, () -> request(encode, decode));
    }

    private static String request(Function<String, JsonNode> encode, Function<JsonNode, String> decode) {
        try (ProtocolSession<String, String> session = Procwright.command(
                        ProtocolAdaptersTestWorker.command("json-lines"))
                .protocolSession(typedFactory(encode, decode, ProtocolAdapters.jsonLinesSession(1024)))
                .withRequestTimeout(REQUEST_TIMEOUT)
                .open()) {
            return session.request("request");
        }
    }

    private static <I, O> Supplier<ProtocolAdapter<I, O>> typedFactory(
            Function<? super I, ? extends JsonNode> encode,
            Function<? super JsonNode, ? extends O> decode,
            Supplier<? extends ProtocolAdapter<JsonNode, JsonNode>> transportFactory) {
        return ProtocolAdapters.typedJsonSession(encode, decode, transportFactory);
    }

    private static ProtocolSessionException protocolFailure(ProtocolSessionException.Reason reason) {
        return new ProtocolSessionException(reason, new ProtocolTranscript("", false, false), "callback failure");
    }

    private static String requestAfter(CountDownLatch start, PooledProtocolSession<String, String> pool, String request)
            throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Pool requests did not start");
        }
        return pool.request(request);
    }

    private static ProtocolAdapter<String, String> factoryAfter(
            CountDownLatch start, Supplier<ProtocolAdapter<String, String>> factory) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Factory calls did not start");
        }
        return factory.get();
    }

    private static void awaitOverlap(CountDownLatch entered) {
        entered.countDown();
        try {
            if (!entered.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Callbacks did not overlap");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for callback overlap", exception);
        }
    }
}
