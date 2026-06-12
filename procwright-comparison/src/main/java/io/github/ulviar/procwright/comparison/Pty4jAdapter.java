/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.time.Duration;
import java.util.HashMap;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

final class Pty4jAdapter implements CandidateAdapter {

    @Override
    public String id() {
        return "pty4j";
    }

    @Override
    public String displayName() {
        return "Pty4J";
    }

    @Override
    public String scope() {
        return "PTY-backed process transport";
    }

    @Override
    public CommandOutcome ptyProbe(Duration timeout) {
        long started = System.nanoTime();
        if (ProcessSupport.isWindows()) {
            return new CommandOutcome(
                    OutcomeStatus.SKIPPED,
                    OptionalInt.empty(),
                    false,
                    new byte[0],
                    false,
                    new byte[0],
                    false,
                    Duration.ZERO,
                    "POSIX shell probe skipped on Windows");
        }
        try {
            HashMap<String, String> env = new HashMap<>(System.getenv());
            env.putIfAbsent("TERM", "xterm");
            PtyProcess process = new PtyProcessBuilder()
                    .setCommand(new String[] {
                        "sh", "-c", "if [ -t 0 ] && [ -t 1 ]; then echo pty:true; else echo pty:false; fi"
                    })
                    .setEnvironment(env)
                    .start();
            byte[] output =
                    ProcessSupport.readAllBytesBounded(process.getInputStream(), timeout, process::destroyForcibly);
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String text = new String(output, java.nio.charset.StandardCharsets.UTF_8);
            return new CommandOutcome(
                    exited && text.contains("pty:true") ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                    exited ? OptionalInt.of(process.exitValue()) : OptionalInt.empty(),
                    !exited,
                    output,
                    false,
                    new byte[0],
                    false,
                    ProcessSupport.elapsedSince(started),
                    text.strip());
        } catch (Throwable throwable) {
            return ProcessSupport.failure(started, throwable);
        }
    }
}
