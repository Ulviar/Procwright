/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

final class NuProcessAdapter implements CandidateAdapter {

    @Override
    public String id() {
        return "nuprocess";
    }

    @Override
    public String displayName() {
        return "NuProcess";
    }

    @Override
    public String scope() {
        return "non-blocking native process I/O";
    }

    @Override
    public CommandOutcome run(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        BoundedCapture stdout = new BoundedCapture(captureLimit);
        BoundedCapture stderr = new BoundedCapture(captureLimit);
        try {
            NuProcessBuilder builder = new NuProcessBuilder(request.command());
            if (request.workingDirectory() != null) {
                builder.setCwd(request.workingDirectory());
            }
            builder.environment().putAll(request.environment());
            Handler handler = new Handler(stdout, stderr, request.stdin());
            builder.setProcessListener(handler);
            NuProcess process = builder.start();
            int exit = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = process.isRunning();
            if (timedOut) {
                process.destroy(true);
                exit = process.waitFor(5, TimeUnit.SECONDS);
            }
            return new CommandOutcome(
                    timedOut ? OutcomeStatus.TIMEOUT : OutcomeStatus.PASS,
                    OptionalInt.of(exit),
                    timedOut,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    ProcessSupport.elapsedSince(started),
                    timedOut ? "process still running after timeout" : "");
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    @Override
    public CommandOutcome stream(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        BoundedCapture stdout = new BoundedCapture(captureLimit);
        BoundedCapture stderr = new BoundedCapture(captureLimit);
        try {
            NuProcessBuilder builder = new NuProcessBuilder(request.command());
            if (request.workingDirectory() != null) {
                builder.setCwd(request.workingDirectory());
            }
            builder.environment().putAll(request.environment());
            Handler handler = new Handler(stdout, stderr, request.stdin());
            builder.setProcessListener(handler);
            NuProcess process = builder.start();
            int exit = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = process.isRunning();
            if (timedOut) {
                process.destroy(true);
                exit = process.waitFor(5, TimeUnit.SECONDS);
            }
            return new CommandOutcome(
                    timedOut ? OutcomeStatus.TIMEOUT : OutcomeStatus.PASS,
                    OptionalInt.of(exit),
                    timedOut,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    ProcessSupport.elapsedSince(started),
                    "NuProcess callback; observedWhileRunning=" + handler.observedWhileRunning());
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    private static final class Handler extends NuAbstractProcessHandler {
        private final BoundedCapture stdout;
        private final BoundedCapture stderr;
        private final byte[] stdin;
        private NuProcess process;
        private boolean wrote;
        private final java.util.concurrent.atomic.AtomicBoolean observedWhileRunning =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Handler(BoundedCapture stdout, BoundedCapture stderr, byte[] stdin) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdin = stdin;
        }

        @Override
        public void onStart(NuProcess process) {
            this.process = process;
            if (stdin.length == 0) {
                process.closeStdin(false);
            } else {
                process.wantWrite();
            }
        }

        @Override
        public boolean onStdinReady(ByteBuffer buffer) {
            if (!wrote) {
                buffer.put(stdin);
                buffer.flip();
                wrote = true;
            }
            process.closeStdin(false);
            return false;
        }

        @Override
        public void onStdout(ByteBuffer buffer, boolean closed) {
            capture(buffer, stdout);
        }

        @Override
        public void onStderr(ByteBuffer buffer, boolean closed) {
            capture(buffer, stderr);
        }

        private boolean observedWhileRunning() {
            return observedWhileRunning.get();
        }

        private void capture(ByteBuffer buffer, BoundedCapture capture) {
            if (!buffer.hasRemaining()) {
                return;
            }
            if (process != null && process.isRunning()) {
                observedWhileRunning.set(true);
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            try {
                capture.write(bytes);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }
    }
}
