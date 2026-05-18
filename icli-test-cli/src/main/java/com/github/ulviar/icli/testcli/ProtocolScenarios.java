package com.github.ulviar.icli.testcli;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ProtocolScenarios {

    private ProtocolScenarios() {}

    static int lineRepl(ScenarioContext context) throws Exception {
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
                if (line.startsWith(":sleep")) {
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
