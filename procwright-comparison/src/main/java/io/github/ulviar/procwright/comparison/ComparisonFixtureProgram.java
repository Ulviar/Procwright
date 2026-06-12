/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Deterministic child process used by the comparison harness.
 */
public final class ComparisonFixtureProgram {

    private ComparisonFixtureProgram() {}

    /**
     * Runs the requested deterministic fixture command.
     *
     * @param args fixture command name followed by command-specific arguments
     * @throws Exception when the fixture command fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("fixture command is required");
        }
        switch (args[0]) {
            case "success" -> success();
            case "non-zero" -> nonZero();
            case "stdin-echo" -> stdinEcho();
            case "env" -> env(args);
            case "large-stdout" -> writeRepeated(System.out, args);
            case "large-stderr" -> writeRepeated(System.err, args);
            case "sleep" -> sleep(args);
            case "stream" -> stream(args);
            case "line-repl" -> lineRepl();
            case "prompt" -> prompt();
            default -> throw new IllegalArgumentException("unknown fixture command: " + Arrays.toString(args));
        }
    }

    private static void success() {
        System.out.print("ok\n");
        System.err.print("diagnostic:clean\n");
    }

    private static void nonZero() {
        System.out.print("stdout:diagnostic\n");
        System.err.print("stderr:diagnostic\n");
        System.exit(7);
    }

    private static void stdinEcho() throws IOException {
        byte[] input = System.in.readAllBytes();
        System.out.print("stdin:" + new String(input, StandardCharsets.UTF_8));
    }

    private static void env(String[] args) {
        requireArgs(args, 2);
        System.out.print("env:" + System.getenv(args[1]) + "\n");
    }

    private static void writeRepeated(java.io.PrintStream stream, String[] args) {
        requireArgs(args, 3);
        int bytes = Integer.parseInt(args[1]);
        byte value = args[2].getBytes(StandardCharsets.UTF_8)[0];
        byte[] block = new byte[Math.min(8192, Math.max(1, bytes))];
        Arrays.fill(block, value);
        int remaining = bytes;
        while (remaining > 0) {
            int count = Math.min(block.length, remaining);
            stream.write(block, 0, count);
            remaining -= count;
        }
        stream.flush();
    }

    private static void sleep(String[] args) throws InterruptedException {
        requireArgs(args, 2);
        System.out.print("started\n");
        System.out.flush();
        Thread.sleep(Long.parseLong(args[1]));
        System.out.print("finished\n");
    }

    private static void stream(String[] args) throws InterruptedException {
        requireArgs(args, 3);
        int count = Integer.parseInt(args[1]);
        long delayMillis = Long.parseLong(args[2]);
        for (int index = 0; index < count; index++) {
            System.out.print("out:" + index + "\n");
            System.out.flush();
            System.err.print("err:" + index + "\n");
            System.err.flush();
            Thread.sleep(delayMillis);
        }
    }

    private static void lineRepl() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.print("response:" + line + "\n");
                System.out.flush();
            }
        }
    }

    private static void prompt() throws IOException {
        System.out.print("ready> ");
        System.out.flush();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            System.out.print("accepted:" + line + "\n");
            System.out.flush();
        }
    }

    private static void requireArgs(String[] args, int expected) {
        if (args.length < expected) {
            throw new IllegalArgumentException("expected at least " + expected + " arguments");
        }
    }
}
