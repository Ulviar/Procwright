package io.github.ulviar.procwright.testcli;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

final class InputScenarios {

    private InputScenarios() {}

    static int stdinEcho(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        byte[] input = readInput(context, options.integer("max-bytes", Integer.MAX_VALUE));
        String mode = options.string("mode", "text");
        switch (mode) {
            case "hex" -> context.stdoutLine(HexFormat.of().formatHex(input));
            case "bytes-count" -> context.stdoutLine("bytes:" + input.length);
            case "text" -> context.stdoutText(options.string("prefix", "") + new String(input, context.charset()));
            default -> throw new IllegalArgumentException("unknown stdin-echo mode: " + mode);
        }
        return options.integer("exit-code", 0);
    }

    static int ignoreStdin(ScenarioContext context) throws Exception {
        if (context.options().bool("started", true)) {
            context.stdoutLine(context.options().string("started-text", "started"));
        }
        context.sleepMillis(context.options().longValue("millis", 1000));
        return context.options().integer("exit-code", 0);
    }

    private static byte[] readInput(ScenarioContext context, int maxBytes) throws Exception {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("max-bytes must not be negative");
        }
        long delayMillis = context.options().longValue("delay-per-byte-millis", 0);
        if (delayMillis == 0 && maxBytes == Integer.MAX_VALUE) {
            return context.stdin().readAllBytes();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() < maxBytes) {
            int value = context.stdin().read();
            if (value < 0) {
                break;
            }
            output.write(value);
            context.sleepMillis(delayMillis);
        }
        return output.toByteArray();
    }
}
