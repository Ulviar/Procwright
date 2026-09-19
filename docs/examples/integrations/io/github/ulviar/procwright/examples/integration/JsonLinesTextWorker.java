/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.integration;

import io.github.ulviar.procwright.command.CommandSpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import tools.jackson.databind.json.JsonMapper;

/** Small bundled worker for the service and pool demos; stdout carries only JSON Lines. */
public final class JsonLinesTextWorker {

    private JsonLinesTextWorker() {}

    public static void main(String[] args) throws Exception {
        var json = JsonMapper.builder().build();
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            String text = json.readTree(line).required("text").stringValue();
            var response = json.createObjectNode()
                    .put("codePoints", text.codePointCount(0, text.length()))
                    .put("utf8Bytes", text.getBytes(StandardCharsets.UTF_8).length);
            System.out.write(json.writeValueAsBytes(response));
            System.out.write('\n');
            System.out.flush();
        }
    }

    static CommandSpec command(String... args) {
        if (args.length != 0) {
            return CommandSpec.of(args[0]).withArgs(Arrays.copyOfRange(args, 1, args.length));
        }
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return CommandSpec.of(Path.of(System.getProperty("java.home"), "bin", executable)
                        .toString())
                .withArgs("-cp", System.getProperty("java.class.path"), JsonLinesTextWorker.class.getName());
    }
}
