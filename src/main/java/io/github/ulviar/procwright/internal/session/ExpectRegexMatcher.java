/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ExpectMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns bounded regular-expression evaluation and reconciles its result with expect session state.
 */
final class ExpectRegexMatcher {

    private final ExpectSessionState state;
    private final BoundedTaskLimiter limiter;
    private final Evaluator evaluator;

    ExpectRegexMatcher(ExpectSessionState state, BoundedTaskLimiter limiter, Evaluator evaluator) {
        this.state = Objects.requireNonNull(state, "state");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    ExpectMatch match(Pattern pattern, long deadlineNanos, String timeoutMessage, String transcriptAction) {
        state.beginOperation(timeoutMessage, transcriptAction);
        AtomicReference<Thread> evaluatorThread = new AtomicReference<>();
        while (true) {
            Attempt attempt;
            try {
                attempt = BoundedTaskRunner.run(
                        limiter,
                        "procwright-expect-regex-",
                        deadlineNanos,
                        state.terminalCancellationToken(),
                        () -> {
                            evaluatorThread.set(Thread.currentThread());
                            ExpectSessionState.RegexSnapshot snapshot = state.regexSnapshot();
                            Evaluation evaluation = evaluator.find(pattern, snapshot.output(), snapshot.searchStart());
                            return new Attempt(snapshot, evaluation);
                        });
            } catch (TimeoutException exception) {
                throw state.terminalFailureOrTimeout(timeoutMessage);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                state.throwIfTerminal(timeoutMessage);
                throw state.failure("Interrupted while matching expected output", exception);
            } catch (BoundedTaskRunner.TaskCancelledException exception) {
                throw state.terminalFailureRequired(timeoutMessage);
            } catch (ExecutionException exception) {
                throw state.arbitrateRegexFailure(
                        timeoutMessage,
                        Objects.requireNonNull(exception.getCause(), "regex failure cause"),
                        evaluatorThread.get());
            }

            ExpectMatch match = state.acceptRegexEvaluation(
                    attempt.snapshot(), attempt.evaluation(), deadlineNanos, timeoutMessage);
            if (match != null) {
                return match;
            }
        }
    }

    static Evaluation evaluate(Pattern pattern, String text, int searchStart) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(searchStart, text.length());
        if (!matcher.find()) {
            return null;
        }
        return new Evaluation(matcher.start(), matcher.end(), matcher.group(), groupsOf(matcher));
    }

    private static List<String> groupsOf(Matcher matcher) {
        ArrayList<String> groups = new ArrayList<>(matcher.groupCount());
        for (int index = 1; index <= matcher.groupCount(); index++) {
            String group = matcher.group(index);
            groups.add(group == null ? "" : group);
        }
        return groups;
    }

    @FunctionalInterface
    interface Evaluator {

        Evaluation find(Pattern pattern, String text, int searchStart);
    }

    record Evaluation(int start, int end, String matched, List<String> groups) {

        Evaluation {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid regex match range");
            }
            Objects.requireNonNull(matched, "matched");
            groups = List.copyOf(groups);
        }
    }

    private record Attempt(ExpectSessionState.RegexSnapshot snapshot, Evaluation evaluation) {

        private Attempt {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
