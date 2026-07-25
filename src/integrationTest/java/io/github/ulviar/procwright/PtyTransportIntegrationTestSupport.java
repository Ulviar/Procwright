/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ulviar.procwright.session.Session;
import java.io.BufferedReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

final class PtyTransportIntegrationTestSupport {

    private PtyTransportIntegrationTestSupport() {}

    static String readUntil(Session session, BufferedReader reader, String prefix) throws Exception {
        return readUntilMatch(session, reader, line -> line.startsWith(prefix), "line with prefix " + prefix);
    }

    static String readUntilContaining(Session session, BufferedReader reader, String text) throws Exception {
        return readUntilMatch(session, reader, line -> line.contains(text), "line containing " + text);
    }

    static void assumePosixShellAvailable() {
        assumeFalse(isWindows(), "POSIX shell fixture requires sh");
    }

    private static String readUntilMatch(
            Session session, BufferedReader reader, Predicate<String> matcher, String description) throws Exception {
        // The worker lets the deadline preempt readLine(); closing the session unblocks the timed-out read.
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "procwright-pty-read-until");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> match = executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (matcher.test(line)) {
                        return line;
                    }
                }
                throw new AssertionError("missing " + description);
            });
            try {
                return match.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                session.close();
                match.cancel(true);
                throw new AssertionError("timed out waiting for " + description, exception);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
