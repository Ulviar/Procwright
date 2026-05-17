package com.github.ulviar.icli.comparison;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import net.sf.expectit.ExpectBuilder;

final class ExpectItAdapter implements CandidateAdapter {

    @Override
    public String id() {
        return "expectit";
    }

    @Override
    public String displayName() {
        return "ExpectIt";
    }

    @Override
    public String scope() {
        return "Expect-style prompt automation over streams";
    }

    @Override
    public CommandOutcome expectPrompt(CommandRequest request, Duration timeout) {
        long started = System.nanoTime();
        try {
            Process process = new ProcessBuilder(request.command()).start();
            try (net.sf.expectit.Expect expect = new ExpectBuilder()
                    .withInputs(process.getInputStream())
                    .withOutput(process.getOutputStream())
                    .withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .build()) {
                expect.expect(net.sf.expectit.matcher.Matchers.contains("ready> "));
                expect.sendLine("status");
                expect.expect(net.sf.expectit.matcher.Matchers.contains("accepted:status"));
            }
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            return new CommandOutcome(
                    exited ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                    OptionalInt.of(process.exitValue()),
                    !exited,
                    new byte[0],
                    false,
                    new byte[0],
                    false,
                    ProcessSupport.elapsedSince(started),
                    "");
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }
}
