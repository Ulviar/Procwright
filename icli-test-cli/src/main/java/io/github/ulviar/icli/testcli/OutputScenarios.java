package io.github.ulviar.icli.testcli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

final class OutputScenarios {

    private OutputScenarios() {}

    static int stream(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        int count = options.integer("count", 10);
        int stderrEvery = Math.max(0, options.integer("stderr-every", 1));
        long delayMillis = options.longValue("delay-millis", 50);
        boolean newline = options.bool("newline", true);
        boolean flush = options.bool("flush", true);
        for (int index = 0; index < count; index++) {
            String stdout = renderTemplate(options.string("stdout-template", "out-%d"), index);
            writeChunk(context.stdout(), stdout, newline, flush, context);
            if (stderrEvery > 0 && index % stderrEvery == 0) {
                String stderr = renderTemplate(options.string("stderr-template", "err-%d"), index);
                writeChunk(context.stderr(), stderr, newline, flush, context);
            }
            context.sleepMillis(delayMillis);
        }
        context.flush();
        return options.integer("exit-code", 0);
    }

    static int longRun(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        int ticks = options.integer("ticks", 10);
        long intervalMillis = options.longValue("interval-millis", 100);
        int stderrEvery = Math.max(0, options.integer("stderr-every", 0));
        String payload = options.string("payload", "");
        for (int index = 0; index < ticks; index++) {
            context.stdoutLine("tick:" + index + payload);
            if (stderrEvery > 0 && index % stderrEvery == 0) {
                context.stderrLine("err-tick:" + index);
            }
            context.sleepMillis(intervalMillis);
        }
        return options.integer("exit-code", 0);
    }

    static int burst(ScenarioContext context) throws IOException {
        CliOptions options = context.options();
        int stdoutBytes = options.byteSize("stdout-bytes", 1024);
        int stderrBytes = options.byteSize("stderr-bytes", 0);
        int blockBytes = options.byteSize("block-bytes", 8192);
        byte stdoutByte = singleByte(options.string("stdout-byte", "O"));
        byte stderrByte = singleByte(options.string("stderr-byte", "E"));
        if (options.bool("stdout-first", true)) {
            context.writeRepeated(context.stdout(), stdoutByte, stdoutBytes, blockBytes);
            context.writeRepeated(context.stderr(), stderrByte, stderrBytes, blockBytes);
        } else {
            context.writeRepeated(context.stderr(), stderrByte, stderrBytes, blockBytes);
            context.writeRepeated(context.stdout(), stdoutByte, stdoutBytes, blockBytes);
        }
        return options.integer("exit-code", 0);
    }

    static int partial(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        context.stdoutText(options.string("stdout", "partial-out"));
        context.stderrText(options.string("stderr", "partial-err"));
        context.sleepMillis(options.longValue("hold-millis", 1000));
        return options.integer("exit-code", 0);
    }

    static int binary(ScenarioContext context) throws IOException {
        CliOptions options = context.options();
        byte[] pattern = binaryPattern(options);
        int repeat = options.integer("repeat", 1);
        String stream = options.string("stream", "stdout").toLowerCase(Locale.ROOT);
        for (int index = 0; index < repeat; index++) {
            if ("stdout".equals(stream) || "both".equals(stream)) {
                context.stdout().write(pattern);
            }
            if ("stderr".equals(stream) || "both".equals(stream)) {
                context.stderr().write(pattern);
            }
        }
        context.flush();
        return options.integer("exit-code", 0);
    }

    static int ansiPrompt(ScenarioContext context) throws Exception {
        context.stdoutText("\u001B[31m" + context.options().string("prompt", "READY") + "\u001B[0m> ");
        context.sleepMillis(context.options().longValue("hold-millis", 1000));
        return context.options().integer("exit-code", 0);
    }

    private static String renderTemplate(String template, int index) {
        return template.replace("%d", Integer.toString(index)).replace("{i}", Integer.toString(index));
    }

    private static void writeChunk(
            OutputStream stream, String text, boolean newline, boolean flush, ScenarioContext context)
            throws IOException {
        stream.write(text.getBytes(context.charset()));
        if (newline) {
            stream.write('\n');
        }
        if (flush) {
            stream.flush();
        }
    }

    private static byte singleByte(String value) {
        if (value.startsWith("0x")) {
            return (byte) Integer.parseInt(value.substring(2), 16);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 1) {
            throw new IllegalArgumentException("byte option must encode to exactly one byte: " + value);
        }
        return bytes[0];
    }

    private static byte[] binaryPattern(CliOptions options) {
        String pattern = options.string("pattern", "nul-ff-ascii");
        return switch (pattern) {
            case "nul-ff-ascii" -> new byte[] {0x00, (byte) 0xff, 0x41};
            case "hex" -> HexFormat.of().parseHex(options.string("hex", ""));
            case "range" -> range(options.byteSize("range-bytes", 256));
            default -> throw new IllegalArgumentException("unknown binary pattern: " + pattern);
        };
    }

    private static byte[] range(int bytes) {
        byte[] pattern = new byte[bytes];
        for (int index = 0; index < bytes; index++) {
            pattern[index] = (byte) index;
        }
        return pattern;
    }
}
