/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import io.github.ulviar.procwright.command.CommandSpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class ProtocolAdaptersTestWorker {

    private ProtocolAdaptersTestWorker() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "json-lines" -> echoJsonLines();
            case "expect-timeout" -> Thread.sleep(30_000L);
            case "expect-eof" -> {
                // Exit without output.
            }
            default -> throw new IllegalArgumentException("Unknown worker mode");
        }
    }

    static CommandSpec command(String mode) {
        return CommandSpec.of(javaExecutable())
                .withArgs(
                        "-cp", System.getProperty("java.class.path"), ProtocolAdaptersTestWorker.class.getName(), mode);
    }

    private static void echoJsonLines() throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            output.write(line);
            output.write('\n');
            output.flush();
        }
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
