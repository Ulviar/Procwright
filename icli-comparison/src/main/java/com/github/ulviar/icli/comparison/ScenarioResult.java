package com.github.ulviar.icli.comparison;

import java.time.Duration;
import java.util.List;

record ScenarioResult(
        String scenarioId,
        String scenario,
        String candidate,
        OutcomeStatus status,
        int attempts,
        int passed,
        Duration medianElapsed,
        Duration totalElapsed,
        String note) {

    static ScenarioResult unsupported(String scenarioId, String scenario, CandidateAdapter candidate, String note) {
        return new ScenarioResult(
                scenarioId,
                scenario,
                candidate.displayName(),
                OutcomeStatus.UNSUPPORTED,
                0,
                0,
                Duration.ZERO,
                Duration.ZERO,
                note);
    }

    static ScenarioResult skipped(String scenarioId, String scenario, CandidateAdapter candidate, String note) {
        return new ScenarioResult(
                scenarioId,
                scenario,
                candidate.displayName(),
                OutcomeStatus.SKIPPED,
                0,
                0,
                Duration.ZERO,
                Duration.ZERO,
                note);
    }

    static ScenarioResult single(
            String scenarioId,
            String scenario,
            CandidateAdapter candidate,
            OutcomeStatus status,
            boolean passed,
            Duration elapsed,
            String note) {
        return new ScenarioResult(
                scenarioId, scenario, candidate.displayName(), status, 1, passed ? 1 : 0, elapsed, elapsed, note);
    }

    static ScenarioResult fromAttempts(
            String scenarioId,
            String scenario,
            CandidateAdapter candidate,
            int attempts,
            int passed,
            List<Duration> elapsed,
            String note) {
        OutcomeStatus status = passed == attempts ? OutcomeStatus.PASS : OutcomeStatus.FAIL;
        return new ScenarioResult(
                scenarioId,
                scenario,
                candidate.displayName(),
                status,
                attempts,
                passed,
                median(elapsed),
                elapsed.stream().reduce(Duration.ZERO, Duration::plus),
                note);
    }

    private static Duration median(List<Duration> elapsed) {
        if (elapsed.isEmpty()) {
            return Duration.ZERO;
        }
        List<Duration> sorted = elapsed.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }
}
