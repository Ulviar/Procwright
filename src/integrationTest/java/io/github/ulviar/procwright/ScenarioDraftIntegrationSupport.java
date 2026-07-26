/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.StreamSession;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ScenarioDraftIntegrationSupport {

    static final long SCENARIO_WATCHDOG_SECONDS = 10;

    private ScenarioDraftIntegrationSupport() {}

    static String interactiveResponse(InteractiveScenario.Draft draft, String request) throws Exception {
        try (Session session = draft.open()) {
            BufferedReader stdout = new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8));
            session.sendLine(request);
            return stdout.readLine();
        }
    }

    static String lineResponse(LineSessionScenario.Draft draft, String request) {
        try (LineSession session = draft.open()) {
            return session.request(request).text();
        }
    }

    static String protocolResponse(ProtocolSessionScenario.Draft<String, String> draft, String request) {
        try (ProtocolSession<String, String> session = draft.open()) {
            return session.request(request);
        }
    }

    static String streamOutput(StreamScenario.Draft draft) throws Exception {
        StringBuilder output = new StringBuilder();
        try (StreamSession session =
                draft.onOutput(chunk -> output.append(chunk.text())).open()) {
            session.onExit().get(5, TimeUnit.SECONDS);
        }
        return output.toString();
    }

    static long interactivePid(InteractiveScenario.Draft draft) throws Exception {
        return responsePid(interactiveResponse(draft, "pid"));
    }

    static long linePid(LineSessionScenario.Draft draft) {
        return responsePid(lineResponse(draft, "pid"));
    }

    static long protocolPid(ProtocolSessionScenario.Draft<String, String> draft) {
        return responsePid(protocolResponse(draft, "pid"));
    }

    static boolean awaitStream(StreamScenario.Draft draft) throws Exception {
        try (StreamSession session = draft.open()) {
            return session.onExit().get(5, TimeUnit.SECONDS).exitCode().orElse(-1) == 0;
        }
    }

    static long responsePid(String response) {
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

    static <T> List<T> invokeConcurrently(Callable<T> firstCall, Callable<T> secondCall) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> first = executor.submit(awaitStart(firstCall, ready, start));
            Future<T> second = executor.submit(awaitStart(secondCall, ready, start));
            if (!ready.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent terminal calls did not reach the start barrier");
            }
            start.countDown();
            return List.of(get(first), get(second));
        } finally {
            start.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent terminal executor did not terminate");
            }
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
            return future.get(SCENARIO_WATCHDOG_SECONDS + 2, TimeUnit.SECONDS);
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
}
