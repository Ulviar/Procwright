/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProcessLivenessTest {

    @Test
    void ordinaryObservationFallsBackToExitValueWhenLivenessIsUnavailable() {
        assertTrue(ProcessLiveness.hasExited(new ObservationProcess(false, new SecurityException("denied"))));
        assertFalse(ProcessLiveness.hasExited(new ObservationProcess(true, new SecurityException("denied"))));
    }

    @Test
    void unavailableOrdinaryLivenessAndExitNeverProveCompletion() {
        assertFalse(ProcessLiveness.hasExited(new ObservationProcess(
                true,
                new SecurityException("liveness denied"),
                new UnsupportedOperationException("exit denied"),
                () -> {})));
    }

    @Test
    void unifiedProcessObservationClassifiesOrdinaryLiveness() throws Exception {
        assertSame(ProcessLiveness.Observation.LIVE, ProcessLiveness.observe(new ObservationProcess(true, null)));
        assertSame(ProcessLiveness.Observation.EXITED, ProcessLiveness.observe(new ObservationProcess(false, null)));
        assertSame(
                ProcessLiveness.Observation.UNOBSERVABLE,
                ProcessLiveness.observe(new ObservationProcess(true, new SecurityException("denied"))));
    }

    @Test
    void unifiedHandleObservationClassifiesOrdinaryLiveness() throws Exception {
        MutableProcessHandle live = new MutableProcessHandle(201);
        MutableProcessHandle exited = new MutableProcessHandle(202);
        exited.destroyForcibly();
        MutableProcessHandle unobservable = new MutableProcessHandle(203) {
            @Override
            public boolean isAlive() {
                throw new SecurityException("denied");
            }
        };

        assertSame(ProcessLiveness.Observation.LIVE, ProcessLiveness.observe(live));
        assertSame(ProcessLiveness.Observation.EXITED, ProcessLiveness.observe(exited));
        assertSame(ProcessLiveness.Observation.UNOBSERVABLE, ProcessLiveness.observe(unobservable));
    }

    @Test
    void cleanupObservationReturnsFailuresSeparatelyInObservationOrder() {
        IllegalStateException livenessFailure = new IllegalStateException("liveness failed");
        AssertionError exitFailure = new AssertionError("exit failed");
        ObservationProcess process = new ObservationProcess(true, livenessFailure, exitFailure, () -> {});

        ProcessLiveness.ExitObservation observation = ProcessLiveness.observeExitForCleanup(process);

        assertSame(ProcessLiveness.Observation.UNOBSERVABLE, observation.state());
        assertEquals(List.of(livenessFailure, exitFailure), observation.events());
        assertEquals(0, livenessFailure.getSuppressed().length);
    }

    @Test
    void cleanupObservationClassifiesCompletionWithoutSyntheticEvents() {
        List<ObservationExpectation> expectations = List.of(
                new ObservationExpectation(new ObservationProcess(false, null), ProcessLiveness.Observation.EXITED),
                new ObservationExpectation(new ObservationProcess(true, null), ProcessLiveness.Observation.LIVE),
                new ObservationExpectation(
                        new ObservationProcess(false, new SecurityException("liveness denied"), null, () -> {}),
                        ProcessLiveness.Observation.EXITED),
                new ObservationExpectation(
                        new ObservationProcess(true, new SecurityException("liveness denied"), null, () -> {}),
                        ProcessLiveness.Observation.LIVE),
                new ObservationExpectation(
                        new ObservationProcess(
                                true,
                                new SecurityException("liveness denied"),
                                new UnsupportedOperationException("exit denied"),
                                () -> {}),
                        ProcessLiveness.Observation.UNOBSERVABLE));

        for (ObservationExpectation expectation : expectations) {
            ProcessLiveness.ExitObservation observation = ProcessLiveness.observeExitForCleanup(expectation.process());

            assertSame(expectation.expected(), observation.state());
            assertEquals(List.of(), observation.events());
        }
    }

    private record ObservationExpectation(ObservationProcess process, ProcessLiveness.Observation expected) {}

    private static class ObservationProcess extends Process {

        private final boolean alive;
        private final Throwable livenessFailure;
        private final Throwable exitFailure;
        private final Runnable beforeLiveness;
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger exitValueCalls = new AtomicInteger();

        private ObservationProcess(boolean alive, Throwable livenessFailure) {
            this(alive, livenessFailure, null, () -> {});
        }

        private ObservationProcess(
                boolean alive, Throwable livenessFailure, Throwable exitFailure, Runnable beforeLiveness) {
            this.alive = alive;
            this.livenessFailure = livenessFailure;
            this.exitFailure = exitFailure;
            this.beforeLiveness = beforeLiveness;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            exitValueCalls.incrementAndGet();
            throwIfPresent(exitFailure);
            if (alive) {
                throw new IllegalThreadStateException("alive");
            }
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public boolean isAlive() {
            livenessCalls.incrementAndGet();
            beforeLiveness.run();
            throwIfPresent(livenessFailure);
            return alive;
        }

        private static void throwIfPresent(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
