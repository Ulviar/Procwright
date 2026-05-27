package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import java.util.Comparator;
import java.util.List;

/**
 * @hidden
 */
public final class CommandEchoSupport {

    private CommandEchoSupport() {}

    public static CommandEcho from(LaunchPlan launchPlan) {
        List<String> environmentNames = launchPlan.environment().keySet().stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        return new CommandEcho(
                launchPlan.command().get(0),
                launchPlan.command().size() - 1,
                launchPlan.workingDirectory(),
                environmentNames,
                launchPlan.outputMode(),
                launchPlan.terminalPolicy());
    }

    public static String redactedSummary(LaunchPlan launchPlan) {
        return redactedSummary(launchPlan.command());
    }

    public static String redactedSummary(List<String> command) {
        List<String> commandSnapshot = List.copyOf(command);
        if (commandSnapshot.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        return "executable=" + commandSnapshot.get(0) + ", argumentCount=" + (commandSnapshot.size() - 1);
    }
}
