/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs comparison scenarios for process-execution libraries.
 */
public final class ComparisonRunner {

    private static final int CAPTURE_LIMIT = 64 * 1024;
    private static final Duration DEFAULT_SCENARIO_TIMEOUT = Duration.ofSeconds(5);
    private static final Path DEFAULT_REPORT_PATH = Path.of("build", "reports", "comparison", "results.md");

    private final ComparisonOptions options;
    private final List<CandidateAdapter> candidates;

    private ComparisonRunner(ComparisonOptions options) {
        this.options = options;
        this.candidates = List.of(
                new ProcwrightCandidateAdapter(),
                new JdkProcessBuilderAdapter(),
                new CommonsExecAdapter(),
                new ZtExecAdapter(),
                new NuProcessAdapter(),
                new ExpectItAdapter(),
                new Pty4jAdapter());
    }

    /**
     * Runs all comparison scenarios and writes a Markdown report.
     *
     * @param args optional output path, or {@code --verify <output>} for the regression gate; defaults to
     *     {@code build/reports/comparison/results.md}
     * @throws Exception when a scenario or report write fails unexpectedly
     */
    public static void main(String[] args) throws Exception {
        boolean verify = args.length > 0 && "--verify".equals(args[0]);
        ComparisonRunner runner = new ComparisonRunner(ComparisonOptions.fromSystemProperties());
        Path output = outputPath(args, verify);
        List<ScenarioResult> results = runner.runAll();
        runner.writeReport(output, results);
        if (verify) {
            verifyRegressionGate(results);
        }
        System.out.println("Wrote comparison report: " + output.toAbsolutePath());
    }

    private List<ScenarioResult> runAll() {
        ArrayList<ScenarioResult> results = new ArrayList<>();
        for (CandidateAdapter candidate : candidates) {
            results.add(repeatedRun("S01", "one-shot success", candidate, this::successRequest, this::assertSuccess));
            results.add(
                    repeatedRun("S02", "non-zero diagnostics", candidate, this::nonZeroRequest, this::assertNonZero));
            results.add(repeatedRun("S03", "stdin echo", candidate, this::stdinRequest, this::assertStdin));
            results.add(repeatedRun("S04", "environment override", candidate, this::envRequest, this::assertEnv));
            results.add(repeatedRun(
                    "S05a",
                    "large stdout bounded capture",
                    candidate,
                    this::largeStdoutRequest,
                    this::assertLargeStdout));
            results.add(repeatedRun(
                    "S05b",
                    "large stderr bounded capture",
                    candidate,
                    this::largeStderrRequest,
                    this::assertLargeStderr));
            results.add(timeoutChurn(candidate));
            results.add(streaming(candidate));
            results.add(lineSession(candidate));
            results.add(expectPrompt(candidate));
            results.add(ptyProbe(candidate));
            results.add(poolingCapability(candidate));
            results.add(adapterSafetyCapability(candidate));
        }
        return results;
    }

    private ScenarioResult repeatedRun(
            String id,
            String scenario,
            CandidateAdapter candidate,
            RequestFactory requestFactory,
            OutcomeAssertion assertion) {
        ArrayList<Duration> elapsed = new ArrayList<>();
        int passed = 0;
        String note = "";
        for (int index = 0; index < options.iterations(); index++) {
            long attemptStarted = System.nanoTime();
            try {
                CommandOutcome outcome = candidate.run(requestFactory.create(), CAPTURE_LIMIT);
                if (outcome.status() == OutcomeStatus.UNSUPPORTED) {
                    return ScenarioResult.unsupported(id, scenario, candidate, outcome.note());
                }
                elapsed.add(outcome.elapsed());
                if (assertion.accept(outcome)) {
                    passed++;
                } else if (note.isBlank()) {
                    note = outcome.note().isBlank() ? summarize(outcome) : outcome.note();
                }
            } catch (Exception exception) {
                elapsed.add(ProcessSupport.elapsedSince(attemptStarted));
                if (note.isBlank()) {
                    note = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                }
            }
        }
        return ScenarioResult.fromAttempts(id, scenario, candidate, options.iterations(), passed, elapsed, note);
    }

    private ScenarioResult timeoutChurn(CandidateAdapter candidate) {
        String id = "S06";
        String scenario = "timeout churn";
        CommandOutcome probe;
        long probeStarted = System.nanoTime();
        try {
            probe = candidate.run(timeoutRequest(), CAPTURE_LIMIT);
        } catch (Exception exception) {
            return ScenarioResult.fromAttempts(
                    id,
                    scenario,
                    candidate,
                    1,
                    0,
                    List.of(ProcessSupport.elapsedSince(probeStarted)),
                    describe(exception));
        }
        if (probe.status() == OutcomeStatus.UNSUPPORTED) {
            return ScenarioResult.unsupported(id, scenario, candidate, probe.note());
        }

        long started = System.nanoTime();
        ExecutorService executor = Executors.newCachedThreadPool();
        ArrayList<Future<CommandOutcome>> futures = new ArrayList<>();
        ArrayList<Duration> elapsed = new ArrayList<>();
        int passed = 0;
        String note = "";
        try {
            for (int index = 0; index < options.timeoutParallelism(); index++) {
                futures.add(executor.submit(() -> candidate.run(timeoutRequest(), CAPTURE_LIMIT)));
            }
            for (Future<CommandOutcome> future : futures) {
                CommandOutcome outcome = future.get(10, TimeUnit.SECONDS);
                elapsed.add(outcome.elapsed());
                if (outcome.timedOut() || outcome.status() == OutcomeStatus.TIMEOUT) {
                    passed++;
                } else if (note.isBlank()) {
                    note = summarize(outcome);
                }
            }
            return new ScenarioResult(
                    id,
                    scenario,
                    candidate.displayName(),
                    passed == futures.size() ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                    futures.size(),
                    passed,
                    median(elapsed),
                    ProcessSupport.elapsedSince(started),
                    note);
        } catch (Exception exception) {
            if (elapsed.size() < futures.size()) {
                elapsed.add(ProcessSupport.elapsedSince(started));
            }
            return new ScenarioResult(
                    id,
                    scenario,
                    candidate.displayName(),
                    OutcomeStatus.FAIL,
                    futures.size(),
                    passed,
                    median(elapsed),
                    ProcessSupport.elapsedSince(started),
                    note.isBlank() ? describe(exception) : note + "; " + describe(exception));
        } finally {
            executor.shutdownNow();
            awaitExecutorTermination(executor, Duration.ofSeconds(5));
        }
    }

    private ScenarioResult streaming(CandidateAdapter candidate) {
        String id = "S07";
        String scenario = "streaming stdout and stderr";
        long started = System.nanoTime();
        try {
            CommandOutcome outcome = candidate.stream(streamRequest(), CAPTURE_LIMIT);
            if (outcome.status() == OutcomeStatus.UNSUPPORTED) {
                return ScenarioResult.unsupported(id, scenario, candidate, outcome.note());
            }
            boolean hasOutput = outcome.stdoutText().contains("out:7")
                    && outcome.stderrText().contains("err:7");
            boolean callbackObserved = outcome.note().contains("callbackObserved=true")
                    || outcome.note().contains("observedWhileRunning=true");
            boolean ok = hasOutput && callbackObserved;
            OutcomeStatus status = ok && candidate instanceof JdkProcessBuilderAdapter
                    ? OutcomeStatus.MANUAL
                    : ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL;
            String note = ok ? outcome.note() : summarize(outcome);
            return ScenarioResult.single(id, scenario, candidate, status, ok, outcome.elapsed(), note);
        } catch (Exception exception) {
            return failedScenario(id, scenario, candidate, started, exception);
        }
    }

    private ScenarioResult lineSession(CandidateAdapter candidate) {
        String id = "S08";
        String scenario = "line request-response session";
        long started = System.nanoTime();
        try {
            CommandOutcome outcome = candidate.lineSession(lineReplRequest(), Duration.ofSeconds(2));
            if (outcome.status() == OutcomeStatus.UNSUPPORTED) {
                return ScenarioResult.unsupported(id, scenario, candidate, outcome.note());
            }
            boolean ok = outcome.stdoutText().contains("response:alpha")
                    && outcome.stdoutText().contains("response:beta");
            OutcomeStatus status = ok && candidate instanceof JdkProcessBuilderAdapter
                    ? OutcomeStatus.MANUAL
                    : ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL;
            String note = ok && status == OutcomeStatus.MANUAL
                    ? "manual request/response harness"
                    : ok ? "" : summarize(outcome);
            return ScenarioResult.single(id, scenario, candidate, status, ok, outcome.elapsed(), note);
        } catch (Exception exception) {
            return failedScenario(id, scenario, candidate, started, exception);
        }
    }

    private ScenarioResult expectPrompt(CandidateAdapter candidate) {
        String id = "S09";
        String scenario = "expect prompt automation";
        long started = System.nanoTime();
        try {
            CommandOutcome outcome = candidate.expectPrompt(promptRequest(), Duration.ofSeconds(2));
            if (outcome.status() == OutcomeStatus.UNSUPPORTED) {
                return ScenarioResult.unsupported(id, scenario, candidate, outcome.note());
            }
            boolean ok = outcome.status() == OutcomeStatus.PASS;
            OutcomeStatus status = ok && candidate instanceof JdkProcessBuilderAdapter
                    ? OutcomeStatus.MANUAL
                    : ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL;
            String note = ok ? outcome.note() : summarize(outcome);
            return ScenarioResult.single(id, scenario, candidate, status, ok, outcome.elapsed(), note);
        } catch (Exception exception) {
            return failedScenario(id, scenario, candidate, started, exception);
        }
    }

    private ScenarioResult ptyProbe(CandidateAdapter candidate) {
        String id = "S10";
        String scenario = "terminal-required PTY probe";
        long started = System.nanoTime();
        try {
            CommandOutcome outcome = candidate.ptyProbe(Duration.ofSeconds(3));
            if (outcome.status() == OutcomeStatus.UNSUPPORTED) {
                return ScenarioResult.unsupported(id, scenario, candidate, outcome.note());
            }
            if (outcome.status() == OutcomeStatus.SKIPPED) {
                return ScenarioResult.skipped(id, scenario, candidate, outcome.note());
            }
            boolean ok = outcome.status() == OutcomeStatus.PASS;
            return ScenarioResult.fromAttempts(
                    id, scenario, candidate, 1, ok ? 1 : 0, List.of(outcome.elapsed()), outcome.note());
        } catch (Exception exception) {
            return failedScenario(id, scenario, candidate, started, exception);
        }
    }

    private ScenarioResult poolingCapability(CandidateAdapter candidate) {
        String id = "S11";
        String scenario = "warm line-session worker pool";
        if (candidate instanceof ProcwrightCandidateAdapter procwright) {
            CommandOutcome outcome = procwright.pooled(fixtureCommand("line-repl"), Duration.ofSeconds(2));
            boolean ok = outcome.status() == OutcomeStatus.PASS;
            return ScenarioResult.fromAttempts(
                    id, scenario, candidate, 1, ok ? 1 : 0, List.of(outcome.elapsed()), outcome.note());
        }
        return ScenarioResult.unsupported(
                id, scenario, candidate, "no built-in pooled session abstraction; requires custom harness code");
    }

    private ScenarioResult adapterSafetyCapability(CandidateAdapter candidate) {
        String id = "S12";
        String scenario = "structured command-backed tool observation";
        if (candidate instanceof ProcwrightCandidateAdapter) {
            long started = System.nanoTime();
            try {
                ProcwrightCandidateAdapter procwright = (ProcwrightCandidateAdapter) candidate;
                CommandOutcome outcome =
                        procwright.structuredToolObservation(fixtureCommand("success"), fixtureCommand("non-zero"));
                boolean ok = outcome.status() == OutcomeStatus.PASS;
                return ScenarioResult.single(
                        id,
                        scenario,
                        candidate,
                        ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                        ok,
                        outcome.elapsed(),
                        outcome.note());
            } catch (Exception exception) {
                return failedScenario(id, scenario, candidate, started, exception);
            }
        }
        return ScenarioResult.unsupported(
                id,
                scenario,
                candidate,
                "process library returns process data; structured tool observation requires separate adapter layer");
    }

    private CommandRequest successRequest() {
        return CommandRequest.of(fixtureCommand("success"), DEFAULT_SCENARIO_TIMEOUT);
    }

    private CommandRequest nonZeroRequest() {
        return CommandRequest.of(fixtureCommand("non-zero"), DEFAULT_SCENARIO_TIMEOUT);
    }

    private CommandRequest stdinRequest() {
        return CommandRequest.of(fixtureCommand("stdin-echo"), DEFAULT_SCENARIO_TIMEOUT)
                .withStdin("hello\n".getBytes(StandardCharsets.UTF_8));
    }

    private CommandRequest envRequest() {
        return CommandRequest.of(fixtureCommand("env", "PROCWRIGHT_COMPARISON_VALUE"), DEFAULT_SCENARIO_TIMEOUT)
                .withEnvironment("PROCWRIGHT_COMPARISON_VALUE", "configured");
    }

    private CommandRequest largeStdoutRequest() {
        return CommandRequest.of(
                fixtureCommand("large-stdout", Integer.toString(options.largeBytes()), "x"), DEFAULT_SCENARIO_TIMEOUT);
    }

    private CommandRequest largeStderrRequest() {
        return CommandRequest.of(
                fixtureCommand("large-stderr", Integer.toString(options.largeBytes()), "e"), DEFAULT_SCENARIO_TIMEOUT);
    }

    private CommandRequest timeoutRequest() {
        return CommandRequest.of(
                fixtureCommand("sleep", Long.toString(options.timeout().toMillis() * 20)), options.timeout());
    }

    private CommandRequest streamRequest() {
        return CommandRequest.of(fixtureCommand("stream", "8", "10"), DEFAULT_SCENARIO_TIMEOUT);
    }

    private CommandRequest lineReplRequest() {
        return CommandRequest.of(fixtureCommand("line-repl"), DEFAULT_SCENARIO_TIMEOUT);
    }

    private CommandRequest promptRequest() {
        return CommandRequest.of(fixtureCommand("prompt"), DEFAULT_SCENARIO_TIMEOUT);
    }

    private boolean assertSuccess(CommandOutcome outcome) {
        return outcome.exitCode().orElse(-1) == 0
                && outcome.stdoutText().equals("ok\n")
                && outcome.stderrText().contains("diagnostic:clean");
    }

    private boolean assertNonZero(CommandOutcome outcome) {
        return outcome.exitCode().orElse(-1) == 7
                && outcome.stdoutText().contains("stdout:diagnostic")
                && outcome.stderrText().contains("stderr:diagnostic");
    }

    private boolean assertStdin(CommandOutcome outcome) {
        return outcome.exitCode().orElse(-1) == 0 && outcome.stdoutText().equals("stdin:hello\n");
    }

    private boolean assertEnv(CommandOutcome outcome) {
        return outcome.exitCode().orElse(-1) == 0 && outcome.stdoutText().equals("env:configured\n");
    }

    private boolean assertLargeStdout(CommandOutcome outcome) {
        return outcome.exitCode().orElse(-1) == 0
                && outcome.stdout().length == CAPTURE_LIMIT
                && outcome.stdoutTruncated();
    }

    private boolean assertLargeStderr(CommandOutcome outcome) {
        return outcome.exitCode().orElse(-1) == 0
                && outcome.stderr().length == CAPTURE_LIMIT
                && outcome.stderrTruncated();
    }

    private List<String> fixtureCommand(String... args) {
        ArrayList<String> command = new ArrayList<>();
        command.add(ProcessSupport.javaExecutable().toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ComparisonFixtureProgram.class.getName());
        command.addAll(List.of(args));
        return command;
    }

    private void writeReport(Path output, List<ScenarioResult> results) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        StringBuilder report = new StringBuilder();
        report.append("# Process library comparison results\n\n");
        report.append("Generated by `:procwright-comparison:comparisonReport`.\n\n");
        report.append("## Parameters\n\n");
        report.append("- iterations: `").append(options.iterations()).append("`\n");
        report.append("- largeBytes: `").append(options.largeBytes()).append("`\n");
        report.append("- timeoutParallelism: `")
                .append(options.timeoutParallelism())
                .append("`\n");
        report.append("- timeoutMillis: `").append(options.timeout().toMillis()).append("`\n\n");
        report.append("## Candidates\n\n");
        report.append("| Candidate | Scope |\n| --- | --- |\n");
        candidates.stream()
                .sorted(Comparator.comparing(CandidateAdapter::displayName))
                .forEach(candidate -> report.append("| ")
                        .append(escape(candidate.displayName()))
                        .append(" | ")
                        .append(escape(candidate.scope()))
                        .append(" |\n"));
        report.append("\n## Scenario Results\n\n");
        report.append("| Scenario | Candidate | Status | Passed | Median ms | Total ms | Note |\n");
        report.append("| --- | --- | --- | ---: | ---: | ---: | --- |\n");
        for (ScenarioResult result : results) {
            report.append("| ")
                    .append(escape(result.scenarioId() + " " + result.scenario()))
                    .append(" | ")
                    .append(escape(result.candidate()))
                    .append(" | ")
                    .append(result.status())
                    .append(" | ")
                    .append(result.passed())
                    .append("/")
                    .append(result.attempts())
                    .append(" | ")
                    .append(millis(result.medianElapsed()))
                    .append(" | ")
                    .append(millis(result.totalElapsed()))
                    .append(" | ")
                    .append(escape(result.note()))
                    .append(" |\n");
        }
        report.append("\n## Initial Quantitative Summary\n\n");
        appendSummary(report, results);
        report.append("\n## Notes\n\n");
        report.append(
                "- `UNSUPPORTED` means the library does not expose that scenario as a ready abstraction in this harness.\n");
        report.append(
                "- `MANUAL` means the scenario works only through custom harness/state-machine code over a lower-level API.\n");
        report.append("- Timings are local workflow signals, not JMH-grade microbenchmarks.\n");
        report.append(
                "- Qualitative API/documentation/task-fit assessment is performed by independent auditors after this report.\n");
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private void appendSummary(StringBuilder report, List<ScenarioResult> results) {
        report.append("| Candidate | Pass | Fail | Manual | Unsupported | Skipped |\n");
        report.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (CandidateAdapter candidate : candidates) {
            List<ScenarioResult> own = results.stream()
                    .filter(result -> result.candidate().equals(candidate.displayName()))
                    .toList();
            report.append("| ")
                    .append(escape(candidate.displayName()))
                    .append(" | ")
                    .append(count(own, OutcomeStatus.PASS))
                    .append(" | ")
                    .append(count(own, OutcomeStatus.FAIL))
                    .append(" | ")
                    .append(count(own, OutcomeStatus.MANUAL))
                    .append(" | ")
                    .append(count(own, OutcomeStatus.UNSUPPORTED))
                    .append(" | ")
                    .append(count(own, OutcomeStatus.SKIPPED))
                    .append(" |\n");
        }
    }

    private static long count(List<ScenarioResult> results, OutcomeStatus status) {
        return results.stream().filter(result -> result.status() == status).count();
    }

    static Path outputPath(String[] args, boolean verify) {
        if (verify) {
            return args.length > 1 ? Path.of(args[1]) : DEFAULT_REPORT_PATH;
        }
        return args.length == 0 ? DEFAULT_REPORT_PATH : Path.of(args[0]);
    }

    private static void verifyRegressionGate(List<ScenarioResult> results) {
        ArrayList<String> failures = new ArrayList<>();
        results.stream()
                .filter(result -> result.status() == OutcomeStatus.FAIL)
                .forEach(result -> failures.add(result.candidate() + " " + result.scenarioId() + " returned FAIL"));
        for (String scenario :
                new String[] {"S01", "S02", "S03", "S04", "S05a", "S05b", "S06", "S07", "S08", "S09", "S11", "S12"}) {
            requireStatus(results, failures, "Procwright", scenario, OutcomeStatus.PASS);
        }
        requireAnyStatus(results, failures, "Procwright", "S10", OutcomeStatus.PASS, OutcomeStatus.SKIPPED);

        requirePassSet(results, failures, "JDK ProcessBuilder", "S01", "S02", "S03", "S04", "S05a", "S05b", "S06");
        requireStatus(results, failures, "JDK ProcessBuilder", "S07", OutcomeStatus.MANUAL);
        requireStatus(results, failures, "JDK ProcessBuilder", "S08", OutcomeStatus.MANUAL);
        requireStatus(results, failures, "JDK ProcessBuilder", "S09", OutcomeStatus.MANUAL);
        requirePassSet(
                results, failures, "Apache Commons Exec", "S01", "S02", "S03", "S04", "S05a", "S05b", "S06", "S07");
        requirePassSet(
                results, failures, "ZeroTurnaround zt-exec", "S01", "S02", "S03", "S04", "S05a", "S05b", "S06", "S07");
        requirePassSet(results, failures, "NuProcess", "S01", "S02", "S03", "S04", "S05a", "S05b", "S06", "S07");
        requireStatus(results, failures, "ExpectIt", "S09", OutcomeStatus.PASS);
        requireAnyStatus(results, failures, "Pty4J", "S10", OutcomeStatus.PASS, OutcomeStatus.SKIPPED);
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Comparison regression:\n- " + String.join("\n- ", failures));
        }
    }

    private static void requirePassSet(
            List<ScenarioResult> results, List<String> failures, String candidate, String... scenarios) {
        for (String scenario : scenarios) {
            requireStatus(results, failures, candidate, scenario, OutcomeStatus.PASS);
        }
    }

    private static void requireStatus(
            List<ScenarioResult> results,
            List<String> failures,
            String candidate,
            String scenarioId,
            OutcomeStatus expected) {
        ScenarioResult result = results.stream()
                .filter(candidateResult -> candidateResult.candidate().equals(candidate))
                .filter(candidateResult -> candidateResult.scenarioId().equals(scenarioId))
                .findFirst()
                .orElse(null);
        if (result == null) {
            failures.add(candidate + " " + scenarioId + " is missing");
        } else if (result.status() != expected) {
            failures.add(candidate + " " + scenarioId + " expected " + expected + " but was " + result.status());
        }
    }

    private static void requireAnyStatus(
            List<ScenarioResult> results,
            List<String> failures,
            String candidate,
            String scenarioId,
            OutcomeStatus firstExpected,
            OutcomeStatus secondExpected) {
        ScenarioResult result = results.stream()
                .filter(candidateResult -> candidateResult.candidate().equals(candidate))
                .filter(candidateResult -> candidateResult.scenarioId().equals(scenarioId))
                .findFirst()
                .orElse(null);
        if (result == null) {
            failures.add(candidate + " " + scenarioId + " is missing");
        } else if (result.status() != firstExpected && result.status() != secondExpected) {
            failures.add(candidate + " " + scenarioId + " expected " + firstExpected + " or " + secondExpected
                    + " but was " + result.status());
        }
    }

    private static Duration median(List<Duration> elapsed) {
        if (elapsed.isEmpty()) {
            return Duration.ZERO;
        }
        List<Duration> sorted = elapsed.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static String summarize(CommandOutcome outcome) {
        String exit = outcome.exitCode().isPresent()
                ? Integer.toString(outcome.exitCode().getAsInt())
                : "none";
        return "status=%s exit=%s timedOut=%s stdout=%d stderr=%d %s"
                .formatted(
                        outcome.status(),
                        exit,
                        outcome.timedOut(),
                        outcome.stdout().length,
                        outcome.stderr().length,
                        outcome.note());
    }

    private static ScenarioResult failedScenario(
            String scenarioId, String scenario, CandidateAdapter candidate, long started, Exception exception) {
        return ScenarioResult.fromAttempts(
                scenarioId,
                scenario,
                candidate,
                1,
                0,
                List.of(ProcessSupport.elapsedSince(started)),
                describe(exception));
    }

    private static void awaitExecutorTermination(ExecutorService executor, Duration timeout) {
        try {
            executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(Exception exception) {
        return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", "<br>");
    }

    private static String millis(Duration duration) {
        return String.format(Locale.ROOT, "%.3f", duration.toNanos() / 1_000_000.0);
    }

    @FunctionalInterface
    private interface RequestFactory {
        CommandRequest create();
    }

    @FunctionalInterface
    private interface OutcomeAssertion {
        boolean accept(CommandOutcome outcome);
    }
}
