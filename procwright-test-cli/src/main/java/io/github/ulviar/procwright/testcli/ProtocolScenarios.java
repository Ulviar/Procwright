package io.github.ulviar.procwright.testcli;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ProtocolScenarios {

    private ProtocolScenarios() {}

    static int lineRepl(ScenarioContext context) throws Exception {
        return lineRepl(context, false);
    }

    static int controlledLineRepl(ScenarioContext context) throws Exception {
        return lineRepl(context, true);
    }

    static int exitAfterRead(ScenarioContext context) throws Exception {
        try (var reader = context.stdinReader()) {
            reader.readLine();
        }
        return context.options().integer("exit-code", 0);
    }

    static int twoLineDelayRepl(ScenarioContext context) throws Exception {
        long delayMillis = context.options().longValue("delay-millis", 100);
        try (var reader = context.stdinReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                context.stdoutLine("start:" + line);
                context.sleepMillis(delayMillis);
                context.stdoutLine("end:" + line);
            }
        }
        return context.options().integer("exit-code", 0);
    }

    private static int lineRepl(ScenarioContext context, boolean controlsEnabled) throws Exception {
        CliOptions options = context.options();
        String prompt = options.string("prompt", "");
        String responsePrefix = options.string("response-prefix", "response:");
        String exitCommand = options.string("exit-command", ":exit");
        if (!prompt.isEmpty()) {
            context.stdoutText(prompt);
        }
        try (var reader = context.stdinReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(exitCommand)) {
                    int exitCode = commandNumber(line, exitCommand, options.integer("exit-code", 0));
                    context.stdoutLine(options.string("bye-text", "bye"));
                    return exitCode;
                }
                if (controlsEnabled && runControlRequest(line, context)) {
                    // Handled by the simulator control protocol.
                } else if (line.startsWith(":sleep")) {
                    context.sleepMillis(commandNumber(line, ":sleep", 100));
                } else if (line.startsWith(":stderr")) {
                    context.stderrLine(commandText(line, ":stderr"));
                } else if (line.startsWith(":partial")) {
                    context.stdoutText(commandText(line, ":partial"));
                } else if (line.startsWith(":multi")) {
                    int lines = commandNumber(line, ":multi", 2);
                    for (int index = 0; index < lines; index++) {
                        context.stdoutLine("multi:" + index);
                    }
                } else {
                    context.stdoutLine(responsePrefix + line);
                }
                if (!prompt.isEmpty()) {
                    context.stdoutText(prompt);
                }
            }
        }
        return options.integer("exit-code", 0);
    }

    static int lengthLineFrame(ScenarioContext context) throws Exception {
        while (true) {
            String header = readAsciiLine(context.stdin());
            if (header == null) {
                return context.options().integer("exit-code", 0);
            }
            int length = Integer.parseInt(header);
            byte[] body = context.stdin().readNBytes(length);
            if (body.length != length) {
                return context.options().integer("truncated-exit-code", 0);
            }
            context.stdoutLine("len:" + body.length);
            context.stdout().write(body);
            context.stdoutLine("");
            context.stdoutLine("END");
        }
    }

    static int jsonLines(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        int malformedEvery = options.integer("malformed-every", 0);
        long delayMillis = options.longValue("delay-millis", 0);
        int lineNumber = 0;
        try (var reader = context.stdinReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                context.sleepMillis(delayMillis);
                if (malformedEvery > 0 && lineNumber % malformedEvery == 0) {
                    context.stdoutLine("{malformed-json");
                } else {
                    int bytes = line.getBytes(context.charset()).length;
                    context.stdoutLine("{\"ok\":true,\"line\":" + lineNumber + ",\"bytes\":" + bytes + "}");
                }
            }
        }
        return options.integer("exit-code", 0);
    }

    static int contentLength(ScenarioContext context) throws Exception {
        CliOptions options = context.options();
        int frames = 0;
        while (true) {
            byte[] header = readHeader(context.stdin(), options.byteSize("max-header-bytes", 8192));
            if (header.length == 0) {
                return options.integer("exit-code", 0);
            }
            int contentLength = contentLength(header);
            byte[] body = context.stdin().readNBytes(contentLength);
            if (body.length != contentLength) {
                throw new EOFException("EOF before complete Content-Length body");
            }
            frames++;
            if (options.bool("malformed-response", false)) {
                context.stdoutText("Content-Length: 4\r\n\r\n{oops");
            } else {
                byte[] response = ("{\"ok\":true,\"bytes\":" + body.length + "}").getBytes(StandardCharsets.UTF_8);
                context.stdoutText("Content-Length: " + response.length + "\r\n\r\n");
                context.stdout().write(response);
                context.stdout().flush();
            }
            if (frames >= options.integer("max-frames", Integer.MAX_VALUE)) {
                return options.integer("exit-code", 0);
            }
        }
    }

    private static int commandNumber(String line, String command, int defaultValue) {
        String value = commandText(line, command);
        return value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static boolean runControlRequest(String line, ScenarioContext context) throws Exception {
        switch (line) {
            case "pid" ->
                context.stdoutLine("response:pid:" + ProcessHandle.current().pid());
            case "health" -> context.stdoutLine("response:healthy");
            case "reset" -> context.stdoutLine("response:reset");
            case "hold" -> {
                context.sleepMillis(context.options().longValue("hold-millis", 500));
                context.stdoutLine("response:hold");
            }
            case "multi" -> {
                context.stdoutLine("first:multi");
                context.stdoutLine("second:multi");
            }
            case "slow" -> {
                context.stdoutLine("started:slow");
                context.sleepMillis(context.options().longValue("slow-millis", 5000));
            }
            case "slow-response" -> {
                context.sleepMillis(context.options().longValue("slow-response-millis", 5000));
                context.stdoutLine("response:slow");
            }
            case "many" -> {
                for (int index = 0; index < 20; index++) {
                    context.stdoutLine("noise-" + index + "-abcdefghijklmnop");
                }
                context.stdoutLine("done");
            }
            case "stderr-burst" -> {
                context.writeRepeated(context.stderr(), (byte) 'e', 256 * 1024, 8192);
                context.stderrLine("");
                context.stdoutLine("response:stderr-burst");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String commandText(String line, String command) {
        return line.substring(command.length()).stripLeading();
    }

    private static byte[] readHeader(java.io.InputStream input, int maxHeaderBytes) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        while (header.size() < maxHeaderBytes) {
            int value = input.read();
            if (value < 0) {
                if (header.size() == 0) {
                    return new byte[0];
                }
                throw new EOFException("EOF before Content-Length header terminator");
            }
            header.write(value);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && value == '\n') {
                return header.toByteArray();
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = value;
        }
        throw new IOException("Content-Length header exceeds limit");
    }

    private static String readAsciiLine(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
            }
            if (value == '\n') {
                return line.toString(StandardCharsets.US_ASCII);
            }
            if (value != '\r') {
                line.write(value);
            }
        }
    }

    private static int contentLength(byte[] headerBytes) throws IOException {
        String header = new String(headerBytes, StandardCharsets.US_ASCII);
        for (String line : header.split("\\r\\n")) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            if ("content-length".equalsIgnoreCase(name)) {
                int length = Integer.parseInt(line.substring(separator + 1).trim());
                if (length < 0) {
                    throw new IOException("Content-Length must not be negative");
                }
                return length;
            }
        }
        throw new IOException("Content-Length header is missing");
    }
}
