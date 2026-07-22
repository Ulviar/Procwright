/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class PtyEnvironmentProbe {

    private PtyEnvironmentProbe() {}

    public static void main(String[] arguments) throws IOException {
        if (arguments.length == 2 && "--stdin-bytes".equals(arguments[0])) {
            int expectedBytes = Integer.parseInt(arguments[1]);
            byte[] input = System.in.readNBytes(expectedBytes);
            if (input.length != expectedBytes) {
                throw new IOException("incomplete stdin probe input");
            }
            System.out.println("stdin:" + Base64.getEncoder().encodeToString(input));
            return;
        }
        List<String> environmentNames = new ArrayList<>();
        int index = 0;
        while (index < arguments.length && !"--".equals(arguments[index])) {
            environmentNames.add(arguments[index]);
            index++;
        }
        if (index == arguments.length) {
            throw new IllegalArgumentException("missing argument separator");
        }
        index++;
        environmentNames.add("TERM");
        environmentNames.add("COLUMNS");
        environmentNames.add("LINES");
        environmentNames.add("PATH");
        environmentNames.add("SHELL");
        for (String name : environmentNames) {
            String value = System.getenv().getOrDefault(name, "<missing>");
            System.out.println("environment:" + encode(name) + ":" + encode(value));
        }
        while (index < arguments.length) {
            System.out.println("argument:" + encode(arguments[index]));
            index++;
        }
        System.out.println("working-directory:"
                + encode(Path.of("").toAbsolutePath().normalize().toString()));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
