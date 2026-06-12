/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class StressFixtureProgram {

    private StressFixtureProgram() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "large-stdout" -> System.out.print(args[2].repeat(Integer.parseInt(args[1])));
            case "large-stderr" -> {
                System.err.print(args[2].repeat(Integer.parseInt(args[1])));
                System.err.flush();
                System.out.print("done\n");
            }
            case "sleep" -> {
                System.out.print("started\n");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            case "line-repl" -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("hold".equals(line)) {
                        Thread.sleep(250);
                    }
                    System.out.println("response:" + line);
                    System.out.flush();
                }
            }
            default -> throw new IllegalArgumentException("Unknown stress fixture command: " + args[0]);
        }
    }
}
