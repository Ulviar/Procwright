package io.github.ulviar.icli.comparison;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.infra.Blackhole;

final class BenchmarkSupport {

    static final int CAPTURE_LIMIT = 64 * 1024;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration TIMEOUT_SCENARIO_LIMIT = Duration.ofMillis(60);
    static final int LARGE_BYTES = 256 * 1024;

    private BenchmarkSupport() {}

    static CandidateAdapter candidate(String id) {
        return switch (id) {
            case "icli" -> new IcliCandidateAdapter();
            case "jdk-process-builder" -> new JdkProcessBuilderAdapter();
            case "commons-exec" -> new CommonsExecAdapter();
            case "zt-exec" -> new ZtExecAdapter();
            case "nuprocess" -> new NuProcessAdapter();
            case "expectit" -> new ExpectItAdapter();
            case "pty4j" -> new Pty4jAdapter();
            default -> throw new IllegalArgumentException("Unknown benchmark candidate: " + id);
        };
    }

    static CommandRequest successRequest() {
        return CommandRequest.of(fixtureCommand("success"), DEFAULT_TIMEOUT);
    }

    static CommandRequest stdinRequest() {
        return CommandRequest.of(fixtureCommand("stdin-echo"), DEFAULT_TIMEOUT)
                .withStdin("hello\n".getBytes(StandardCharsets.UTF_8));
    }

    static CommandRequest largeStdoutRequest() {
        return CommandRequest.of(fixtureCommand("large-stdout", Integer.toString(LARGE_BYTES), "x"), DEFAULT_TIMEOUT);
    }

    static CommandRequest timeoutRequest() {
        return CommandRequest.of(fixtureCommand("sleep", "1000"), TIMEOUT_SCENARIO_LIMIT);
    }

    static CommandRequest streamRequest() {
        return CommandRequest.of(fixtureCommand("stream", "8", "10"), DEFAULT_TIMEOUT);
    }

    static CommandRequest lineReplRequest() {
        return CommandRequest.of(fixtureCommand("line-repl"), DEFAULT_TIMEOUT);
    }

    static CommandRequest promptRequest() {
        return CommandRequest.of(fixtureCommand("prompt"), DEFAULT_TIMEOUT);
    }

    static List<String> fixtureCommand(String... args) {
        ArrayList<String> command = new ArrayList<>();
        command.add(ProcessSupport.javaExecutable().toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ComparisonFixtureProgram.class.getName());
        command.addAll(List.of(args));
        return command;
    }

    static void consume(Blackhole blackhole, CommandOutcome outcome, OutcomeValidator validator) {
        if (!validator.valid(outcome)) {
            throw new IllegalStateException("Benchmark scenario failed: " + summarize(outcome));
        }
        blackhole.consume(outcome.status());
        blackhole.consume(outcome.exitCode());
        blackhole.consume(outcome.timedOut());
        blackhole.consume(outcome.stdout().length);
        blackhole.consume(outcome.stdoutTruncated());
        blackhole.consume(outcome.stderr().length);
        blackhole.consume(outcome.stderrTruncated());
        blackhole.consume(outcome.note());
    }

    static boolean success(CommandOutcome outcome) {
        return outcome.status() == OutcomeStatus.PASS
                && outcome.exitCode().orElse(-1) == 0
                && outcome.stdoutText().equals("ok\n")
                && outcome.stderrText().contains("diagnostic:clean");
    }

    static boolean stdinEcho(CommandOutcome outcome) {
        return outcome.status() == OutcomeStatus.PASS
                && outcome.exitCode().orElse(-1) == 0
                && outcome.stdoutText().equals("stdin:hello\n");
    }

    static boolean largeStdout(CommandOutcome outcome) {
        return outcome.status() == OutcomeStatus.PASS
                && outcome.exitCode().orElse(-1) == 0
                && outcome.stdout().length == CAPTURE_LIMIT
                && outcome.stdoutTruncated();
    }

    static boolean timedOut(CommandOutcome outcome) {
        return outcome.status() == OutcomeStatus.TIMEOUT || outcome.timedOut();
    }

    static boolean stream(CommandOutcome outcome) {
        return outcome.status() == OutcomeStatus.PASS
                && outcome.stdoutText().contains("out:7")
                && outcome.stderrText().contains("err:7")
                && (outcome.note().contains("observedWhileRunning=true")
                        || outcome.note().contains("callbackObserved=true"));
    }

    static boolean lineSession(CommandOutcome outcome) {
        return (outcome.status() == OutcomeStatus.PASS || outcome.status() == OutcomeStatus.MANUAL)
                && outcome.stdoutText().contains("response:alpha")
                && outcome.stdoutText().contains("response:beta");
    }

    static boolean expectPrompt(CommandOutcome outcome) {
        return outcome.status() == OutcomeStatus.PASS || outcome.status() == OutcomeStatus.MANUAL;
    }

    static boolean pty(CommandOutcome outcome) {
        if (outcome.status() == OutcomeStatus.SKIPPED) {
            throw new IllegalStateException("PTY benchmark skipped: " + outcome.note());
        }
        return outcome.status() == OutcomeStatus.PASS && outcome.stdoutText().contains("pty:true");
    }

    private static String summarize(CommandOutcome outcome) {
        return "status=%s exit=%s timedOut=%s stdout=%d stderr=%d note=%s"
                .formatted(
                        outcome.status(),
                        outcome.exitCode(),
                        outcome.timedOut(),
                        outcome.stdout().length,
                        outcome.stderr().length,
                        outcome.note());
    }

    @FunctionalInterface
    interface OutcomeValidator {
        boolean valid(CommandOutcome outcome);
    }
}
