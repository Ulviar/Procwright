package com.github.ulviar.icli.comparison;

import com.github.ulviar.icli.CommandService;
import com.github.ulviar.icli.command.CommandSpec;
import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.session.LineSessionException;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.PooledLineSessionMetrics;
import com.github.ulviar.icli.session.SessionOptions;
import com.github.ulviar.icli.testcli.TestCli;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs stress comparison scenarios against the reusable iCLI test CLI.
 */
public final class StressComparisonRunner {

    private static final int SMALL_CAPTURE_LIMIT = 4 * 1024;
    private static final int BURST_CAPTURE_LIMIT = 16 * 1024;
    private static final int BURST_PARALLELISM = 12;
    private static final int TIMEOUT_PARALLELISM = 16;
    private static final int FLAKY_SEEDS = 40;

    private final List<CandidateAdapter> candidates = List.of(
            new IcliCandidateAdapter(),
            new JdkProcessBuilderAdapter(),
            new CommonsExecAdapter(),
            new ZtExecAdapter(),
            new NuProcessAdapter());

    private StressComparisonRunner() {}

    /**
     * Runs stress comparison and writes a raw Markdown result table.
     *
     * @param args optional output path
     * @throws Exception when report writing fails
     */
    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Path.of("context/comparison/stress-results.md") : Path.of(args[0]);
        StressComparisonRunner runner = new StressComparisonRunner();
        List<ScenarioResult> results = runner.runAll();
        runner.writeReport(output, results);
        System.out.println("Wrote stress comparison report: " + output.toAbsolutePath());
    }

    private List<ScenarioResult> runAll() {
        ArrayList<ScenarioResult> results = new ArrayList<>();
        for (CandidateAdapter candidate : candidates) {
            results.add(parallelBurst(candidate));
            results.add(seededFlaky(candidate));
            results.add(hangingTimeoutChurn(candidate));
            results.add(processTreeCleanup(candidate));
            results.add(pooledLineSessionMixed(candidate));
        }
        return results;
    }

    private ScenarioResult parallelBurst(CandidateAdapter candidate) {
        return parallelAttempts(
                "ST01",
                "parallel burst stdout/stderr bounded capture",
                candidate,
                BURST_PARALLELISM,
                ignored -> candidate.run(burstRequest(), BURST_CAPTURE_LIMIT),
                StressComparisonRunner::isPassingBurstOutcome);
    }

    private ScenarioResult seededFlaky(CandidateAdapter candidate) {
        ArrayList<Duration> elapsed = new ArrayList<>();
        int passed = 0;
        int succeeded = 0;
        int failed = 0;
        String note = "";
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ArrayList<Future<CommandOutcome>> futures = new ArrayList<>();
            for (int seed = 1; seed <= FLAKY_SEEDS; seed++) {
                int currentSeed = seed;
                futures.add(executor.submit(() -> candidate.run(seededFlakyRequest(currentSeed), SMALL_CAPTURE_LIMIT)));
            }
            for (Future<CommandOutcome> future : futures) {
                CommandOutcome outcome = future.get(8, TimeUnit.SECONDS);
                elapsed.add(outcome.elapsed());
                if (isPassingFlakyOutcome(outcome)) {
                    passed++;
                    if (outcome.exitCode().orElseThrow() == 0) {
                        succeeded++;
                    } else {
                        failed++;
                    }
                } else if (note.isBlank()) {
                    note = summarize(outcome);
                }
            }
        } catch (Exception exception) {
            if (note.isBlank()) {
                note = describe(exception);
            }
        } finally {
            executor.shutdownNow();
            awaitExecutorTermination(executor);
        }

        String counts = "successes=" + succeeded + ", failures=" + failed;
        return result(
                "ST02",
                "seeded flaky success/failure mix",
                candidate,
                FLAKY_SEEDS,
                passed,
                elapsed,
                note.isBlank() ? counts : counts + "; " + note);
    }

    private ScenarioResult hangingTimeoutChurn(CandidateAdapter candidate) {
        return parallelAttempts(
                "ST03",
                "hanging flaky timeout churn",
                candidate,
                TIMEOUT_PARALLELISM,
                ignored -> candidate.run(hangingFlakyRequest(), 1024),
                outcome -> outcome.timedOut() || outcome.status() == OutcomeStatus.TIMEOUT);
    }

    private ScenarioResult processTreeCleanup(CandidateAdapter candidate) {
        String id = "ST04";
        String scenario = "timeout stops parent and spawned child";
        long started = System.nanoTime();
        try {
            CommandOutcome outcome = candidate.run(spawnChildRequest(), SMALL_CAPTURE_LIMIT);
            long childPid = parseChildPid(outcome.stdoutText());
            boolean childStopped = processEventuallyStops(childPid);
            if (!childStopped) {
                ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
            }
            boolean ok = (outcome.timedOut() || outcome.status() == OutcomeStatus.TIMEOUT) && childStopped;
            String note = "childPid=" + childPid + ", childStopped=" + childStopped + ", outcome=" + summarize(outcome);
            return new ScenarioResult(
                    id,
                    scenario,
                    candidate.displayName(),
                    ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                    1,
                    ok ? 1 : 0,
                    outcome.elapsed(),
                    ProcessSupport.elapsedSince(started),
                    note);
        } catch (Exception exception) {
            return new ScenarioResult(
                    id,
                    scenario,
                    candidate.displayName(),
                    OutcomeStatus.FAIL,
                    1,
                    0,
                    ProcessSupport.elapsedSince(started),
                    ProcessSupport.elapsedSince(started),
                    describe(exception));
        }
    }

    private ScenarioResult pooledLineSessionMixed(CandidateAdapter candidate) {
        String id = "ST05";
        String scenario = "pooled line-session mixed success/timeouts";
        if (!(candidate instanceof IcliCandidateAdapter)) {
            return ScenarioResult.unsupported(
                    id,
                    scenario,
                    candidate,
                    "no built-in pooled line-session abstraction; requires a custom pool and protocol state machine");
        }
        long started = System.nanoTime();
        try (PooledLineSession pool = testCliLineService()
                .pooled(call ->
                        call.args("line-repl").maxSize(2).warmupSize(1).acquireTimeout(Duration.ofSeconds(2)))) {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch start = new CountDownLatch(1);
            int timeouts = 0;
            int successes = 0;
            try {
                ArrayList<Future<String>> futures = new ArrayList<>();
                for (int index = 0; index < 10; index++) {
                    int requestIndex = index;
                    futures.add(executor.submit(() -> {
                        start.await();
                        if (requestIndex % 2 == 0) {
                            try {
                                pool.request(":sleep 250", Duration.ofMillis(40));
                                return "unexpected-success";
                            } catch (LineSessionException exception) {
                                return exception.reason() == LineSessionException.Reason.TIMEOUT
                                        ? "timeout"
                                        : "wrong-failure:" + exception.reason();
                            }
                        }
                        return pool.request("ok-" + requestIndex, Duration.ofSeconds(2))
                                .text();
                    }));
                }

                start.countDown();
                for (Future<String> future : futures) {
                    String outcome = future.get(10, TimeUnit.SECONDS);
                    if ("timeout".equals(outcome)) {
                        timeouts++;
                    } else if (outcome.startsWith("response:ok-")) {
                        successes++;
                    }
                }
            } finally {
                executor.shutdownNow();
                awaitExecutorTermination(executor);
            }
            PooledLineSessionMetrics metrics = pool.metrics();
            boolean ok = timeouts == 5
                    && successes == 5
                    && metrics.completedRequests() == 5
                    && metrics.failedRequests() == 5
                    && metrics.size() <= 2
                    && metrics.leased() == 0;
            String note = "successes=%d, timeouts=%d, metrics=%s".formatted(successes, timeouts, metrics);
            return new ScenarioResult(
                    id,
                    scenario,
                    candidate.displayName(),
                    ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                    10,
                    ok ? 10 : successes + timeouts,
                    ProcessSupport.elapsedSince(started),
                    ProcessSupport.elapsedSince(started),
                    note);
        } catch (Exception exception) {
            return new ScenarioResult(
                    id,
                    scenario,
                    candidate.displayName(),
                    OutcomeStatus.FAIL,
                    10,
                    0,
                    ProcessSupport.elapsedSince(started),
                    ProcessSupport.elapsedSince(started),
                    describe(exception));
        }
    }

    private ScenarioResult parallelAttempts(
            String id, String scenario, CandidateAdapter candidate, int attempts, Attempt attempt, OutcomeCheck check) {
        long started = System.nanoTime();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ArrayList<Duration> elapsed = new ArrayList<>();
        int passed = 0;
        String note = "";
        try {
            ArrayList<Future<CommandOutcome>> futures = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                int current = index;
                futures.add(executor.submit(() -> attempt.run(current)));
            }
            for (Future<CommandOutcome> future : futures) {
                CommandOutcome outcome = future.get(15, TimeUnit.SECONDS);
                elapsed.add(outcome.elapsed());
                if (check.accept(outcome)) {
                    passed++;
                } else if (note.isBlank()) {
                    note = summarize(outcome);
                }
            }
        } catch (Exception exception) {
            if (note.isBlank()) {
                note = describe(exception);
            }
        } finally {
            executor.shutdownNow();
            awaitExecutorTermination(executor);
        }
        if (elapsed.isEmpty()) {
            elapsed.add(ProcessSupport.elapsedSince(started));
        }
        return result(id, scenario, candidate, attempts, passed, elapsed, note);
    }

    private static ScenarioResult result(
            String id,
            String scenario,
            CandidateAdapter candidate,
            int attempts,
            int passed,
            List<Duration> elapsed,
            String note) {
        return new ScenarioResult(
                id,
                scenario,
                candidate.displayName(),
                passed == attempts ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                attempts,
                passed,
                median(elapsed),
                elapsed.stream().reduce(Duration.ZERO, Duration::plus),
                note);
    }

    private static boolean isPassingBurstOutcome(CommandOutcome outcome) {
        return outcome.status() == OutcomeStatus.PASS
                && !outcome.timedOut()
                && outcome.exitCode().isPresent()
                && outcome.exitCode().orElseThrow() == 0
                && outcome.stdout().length == BURST_CAPTURE_LIMIT
                && outcome.stderr().length == BURST_CAPTURE_LIMIT
                && outcome.stdoutTruncated()
                && outcome.stderrTruncated();
    }

    private static boolean isPassingFlakyOutcome(CommandOutcome outcome) {
        if (outcome.timedOut() || outcome.exitCode().isEmpty()) {
            return false;
        }
        int exit = outcome.exitCode().orElseThrow();
        if (exit == 0) {
            return outcome.stdoutText().startsWith("flaky:ok:");
        }
        return exit == 75 && outcome.stderrText().startsWith("flaky:failed:");
    }

    private static CommandRequest burstRequest() {
        return CommandRequest.of(
                testCliCommand("burst", "--stdout-bytes=2m", "--stderr-bytes=2m", "--stdout-byte=O", "--stderr-byte=E"),
                Duration.ofSeconds(8));
    }

    private static CommandRequest seededFlakyRequest(int seed) {
        return CommandRequest.of(
                testCliCommand(
                        "flaky",
                        "--seed=" + seed,
                        "--fail-percent=50",
                        "--hang-percent=0",
                        "--max-delay-millis=5",
                        "--failure-exit-code=75"),
                Duration.ofSeconds(2));
    }

    private static CommandRequest hangingFlakyRequest() {
        return CommandRequest.of(
                testCliCommand(
                        "flaky", "--seed=1", "--hang-percent=100", "--fail-percent=0", "--started-text=flaky-hang"),
                Duration.ofMillis(80));
    }

    private static CommandRequest spawnChildRequest() {
        return CommandRequest.of(
                testCliCommand("spawn-child", "--child-scenario=never-exit", "--wait=true"), Duration.ofSeconds(1));
    }

    private static List<String> testCliCommand(String... arguments) {
        ArrayList<String> command = new ArrayList<>();
        command.add(ProcessSupport.javaExecutable().toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TestCli.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    private static CommandService testCliLineService() {
        return new CommandService(
                commandSpec(testCliCommand()),
                RunOptions.defaults(),
                SessionOptions.defaults(),
                LineSessionOptions.defaults().withRequestTimeout(Duration.ofSeconds(2)));
    }

    private static CommandSpec commandSpec(List<String> command) {
        CommandSpec.Builder builder = CommandSpec.builder(command.getFirst());
        if (command.size() > 1) {
            builder.args(command.subList(1, command.size()));
        }
        return builder.build();
    }

    private static long parseChildPid(String stdout) {
        for (String line : stdout.lines().toList()) {
            if (line.startsWith("child:")) {
                return Long.parseLong(line.substring("child:".length()));
            }
        }
        throw new IllegalStateException("missing child pid in stdout: " + stdout);
    }

    private static boolean processEventuallyStops(long pid) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadlineNanos) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        return false;
    }

    private void writeReport(Path output, List<ScenarioResult> results) throws IOException {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Stress comparison raw results\n\n");
        markdown.append("Generated from `icli-comparison:stressComparisonReport` using `:icli-test-cli`.\n\n");
        markdown.append("Candidates: ");
        markdown.append(candidates.stream().map(CandidateAdapter::displayName).toList());
        markdown.append("\n\n");
        markdown.append("| Scenario | Candidate | Status | Passed | Median ms | Total ms | Note |\n");
        markdown.append("| --- | --- | --- | ---: | ---: | ---: | --- |\n");
        results.stream()
                .sorted(Comparator.comparing(ScenarioResult::scenarioId).thenComparing(ScenarioResult::candidate))
                .forEach(result -> markdown.append("| ")
                        .append(result.scenarioId())
                        .append(" ")
                        .append(escape(result.scenario()))
                        .append(" | ")
                        .append(escape(result.candidate()))
                        .append(" | ")
                        .append(result.status())
                        .append(" | ")
                        .append(result.passed())
                        .append("/")
                        .append(result.attempts())
                        .append(" | ")
                        .append(result.medianElapsed().toMillis())
                        .append(" | ")
                        .append(result.totalElapsed().toMillis())
                        .append(" | ")
                        .append(escape(result.note()))
                        .append(" |\n"));
        markdown.append("\n## Scenario definitions\n\n");
        markdown.append("- `ST01`: parallel large stdout/stderr burst with bounded capture.\n");
        markdown.append("- `ST02`: deterministic seeded flaky success/failure mix.\n");
        markdown.append("- `ST03`: parallel timeout churn for hanging processes.\n");
        markdown.append("- `ST04`: timeout cleanup of parent plus spawned child process.\n");
        markdown.append("- `ST05`: mixed pooled line-session successes and request timeouts.\n");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static Duration median(List<Duration> elapsed) {
        if (elapsed.isEmpty()) {
            return Duration.ZERO;
        }
        List<Duration> sorted = elapsed.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static String summarize(CommandOutcome outcome) {
        OptionalInt exit = outcome.exitCode();
        return "status=%s, exit=%s, timeout=%s, stdoutBytes=%d, stderrBytes=%d, stdoutTruncated=%s, stderrTruncated=%s, note=%s"
                .formatted(
                        outcome.status(),
                        exit.isPresent() ? Integer.toString(exit.orElseThrow()) : "empty",
                        outcome.timedOut(),
                        outcome.stdout().length,
                        outcome.stderr().length,
                        outcome.stdoutTruncated(),
                        outcome.stderrTruncated(),
                        outcome.note());
    }

    private static String describe(Exception exception) {
        return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    private static void awaitExecutorTermination(ExecutorService executor) {
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    @FunctionalInterface
    private interface Attempt {
        CommandOutcome run(int index) throws Exception;
    }

    @FunctionalInterface
    private interface OutcomeCheck {
        boolean accept(CommandOutcome outcome);
    }
}
