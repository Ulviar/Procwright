/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class SystemPtyCapabilityProbe implements SystemPtyProvider.ScriptCapabilityProbe {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final int EXPECTED_EXIT = 73;
    static final String ROUND_TRIP_VALUE = "-option-like ' \"quoted\" $(data-only)\nassignment=value\tend\n";

    private final ProcessStarter processStarter;
    private final Duration timeout;

    SystemPtyCapabilityProbe() {
        this(ProcessBuilder::start, PROBE_TIMEOUT);
    }

    SystemPtyCapabilityProbe(ProcessStarter processStarter, Duration timeout) {
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(PtyLaunchAdmission.MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 ns and 10 seconds");
        }
    }

    @Override
    public boolean supports(SystemPtyProvider.ScriptFlavor flavor, SystemPtyProvider.SystemTools tools) {
        Objects.requireNonNull(flavor, "flavor");
        Objects.requireNonNull(tools, "tools");
        if (flavor == SystemPtyProvider.ScriptFlavor.UNAVAILABLE) {
            return false;
        }

        try {
            return PtyLaunchAdmission.run(timeout, context -> probe(flavor, tools, context));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean probe(
            SystemPtyProvider.ScriptFlavor flavor,
            SystemPtyProvider.SystemTools tools,
            PtyLaunchAdmission.Context context)
            throws IOException, InterruptedException {
        SystemPtyProvider.SystemPtySupport provisional = new SystemPtyProvider.SystemPtySupport(
                flavor,
                tools.scriptPath(),
                tools.shellPath(),
                tools.sttyPath(),
                tools.envPath(),
                tools.ddPath(),
                "capability probe");
        SystemPtyProvider.PtyPayload payload = new SystemPtyProvider.PtyPayload(
                List.of(
                        tools.shellPath().toString(),
                        "-c",
                        "if [ -t 0 ] && [ -t 1 ] && [ \"$#\" -eq 3 ] && [ \"$PROCWRIGHT_PTY_PROBE\" = \"$2\" ] && [ \"$3\" = \"$2\" ] && [ \"${LC_ALL+x}\" != x ] && [ \"${LANG+x}\" != x ]; then exit \"$1\"; else exit 74; fi",
                        "procwright-pty-probe",
                        Integer.toString(EXPECTED_EXIT),
                        ROUND_TRIP_VALUE,
                        ROUND_TRIP_VALUE),
                Map.of(
                        "TERM", "dumb",
                        "COLUMNS", "80",
                        "LINES", "24",
                        "PROCWRIGHT_PTY_PROBE", ROUND_TRIP_VALUE));
        PtyBootstrap.Prepared bootstrap = PtyBootstrap.prepare(payload);
        ProcessBuilder builder = new ProcessBuilder(PtyBootstrap.commandFor(provisional, new TerminalSize(80, 24)));
        builder.environment().clear();
        builder.environment().putAll(SystemPtyProvider.wrapperEnvironmentFor(provisional));
        Process process = processStarter.start(builder);
        context.registerProcess(process);
        Process initialized = bootstrap.initialize(process, context);
        Duration remaining = context.remaining();
        if (!initialized.waitFor(Math.max(1L, remaining.toNanos()), TimeUnit.NANOSECONDS)) {
            return false;
        }
        return initialized.exitValue() == EXPECTED_EXIT;
    }

    @FunctionalInterface
    interface ProcessStarter {

        Process start(ProcessBuilder builder) throws IOException;
    }
}
