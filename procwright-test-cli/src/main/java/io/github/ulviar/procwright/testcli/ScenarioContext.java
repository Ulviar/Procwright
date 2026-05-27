package io.github.ulviar.procwright.testcli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

final class ScenarioContext {

    private final CliOptions options;
    private final InputStream stdin;
    private final OutputStream stdout;
    private final OutputStream stderr;
    private final Map<String, String> environment;
    private final Path workingDirectory;

    ScenarioContext(
            CliOptions options,
            InputStream stdin,
            OutputStream stdout,
            OutputStream stderr,
            Map<String, String> environment,
            Path workingDirectory) {
        this.options = Objects.requireNonNull(options, "options");
        this.stdin = Objects.requireNonNull(stdin, "stdin");
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.stderr = Objects.requireNonNull(stderr, "stderr");
        this.environment = Map.copyOf(environment);
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    CliOptions options() {
        return options;
    }

    InputStream stdin() {
        return stdin;
    }

    OutputStream stdout() {
        return stdout;
    }

    OutputStream stderr() {
        return stderr;
    }

    Map<String, String> environment() {
        return environment;
    }

    Path workingDirectory() {
        return workingDirectory;
    }

    Charset charset() {
        return options.charset();
    }

    BufferedReader stdinReader() {
        return new BufferedReader(new InputStreamReader(stdin, charset()));
    }

    void stdoutText(String text) throws IOException {
        writeText(stdout, text);
    }

    void stderrText(String text) throws IOException {
        writeText(stderr, text);
    }

    void stdoutLine(String text) throws IOException {
        stdoutText(text + "\n");
    }

    void stderrLine(String text) throws IOException {
        stderrText(text + "\n");
    }

    void flush() throws IOException {
        stdout.flush();
        stderr.flush();
    }

    void writeRepeated(OutputStream stream, byte value, int bytes, int blockBytes) throws IOException {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        int effectiveBlockBytes = Math.max(1, Math.min(Math.max(1, blockBytes), Math.max(1, bytes)));
        byte[] block = new byte[effectiveBlockBytes];
        Arrays.fill(block, value);
        int remaining = bytes;
        while (remaining > 0) {
            int count = Math.min(block.length, remaining);
            stream.write(block, 0, count);
            remaining -= count;
        }
        stream.flush();
    }

    void sleepMillis(long millis) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must not be negative");
        }
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private void writeText(OutputStream stream, String text) throws IOException {
        stream.write(text.getBytes(charset()));
        stream.flush();
    }
}
