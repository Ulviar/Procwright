package com.github.ulviar.icli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

final class ProcessFixtureProgram {

    private ProcessFixtureProgram() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stdout" -> System.out.print(args[1]);
            case "stderr-exit" -> {
                System.out.print(args[2]);
                System.err.print(args[3]);
                System.exit(Integer.parseInt(args[1]));
            }
            case "args" -> System.out.println(String.join("|", Arrays.copyOfRange(args, 1, args.length)));
            case "cwd-env" -> {
                System.out.println(Path.of("").toAbsolutePath().normalize());
                System.out.println(System.getenv(args[1]));
            }
            case "large-stdout" -> System.out.print(args[2].repeat(Integer.parseInt(args[1])));
            case "large-stderr" -> {
                System.err.print(args[2].repeat(Integer.parseInt(args[1])));
                System.out.print("done\n");
            }
            case "stdout-stderr" -> {
                System.out.print(args[1]);
                System.out.flush();
                System.err.print(args[2]);
                System.err.flush();
            }
            case "sleep" -> {
                System.out.print("started\n");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            case "flood-stderr" -> {
                System.err.write("e".repeat(Integer.parseInt(args[1])).getBytes(StandardCharsets.UTF_8));
                System.err.flush();
                System.out.print("done\n");
            }
            default -> throw new IllegalArgumentException("Unknown fixture command: " + args[0]);
        }
    }
}
