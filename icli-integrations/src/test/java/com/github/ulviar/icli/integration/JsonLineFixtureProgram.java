package com.github.ulviar.icli.integration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class JsonLineFixtureProgram {

    private JsonLineFixtureProgram() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "echo" : args[0];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return;
            }
            switch (mode) {
                case "echo" -> System.out.println(line);
                case "malformed" -> System.out.println("{bad");
                case "slow" -> {
                    Thread.sleep(5000);
                    System.out.println(line);
                }
                default -> throw new IllegalArgumentException("unknown mode: " + mode);
            }
        }
    }
}
