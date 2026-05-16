package com.github.ulviar.icli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

final class ProcessFixtureProgram {

    private ProcessFixtureProgram() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stdout" -> System.out.print(args[1]);
            case "stdin-length" -> System.out.println(System.in.readAllBytes().length);
            case "stdin-echo" -> System.out.write(System.in.readAllBytes());
            case "line-echo" -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("echo:" + line);
                    System.out.flush();
                }
            }
            case "wait-eof" -> System.out.println("eof:" + System.in.readAllBytes().length);
            case "stderr-line" -> {
                System.err.println("error-line");
                System.err.flush();
            }
            case "delayed-stdout-sleep" -> {
                Thread.sleep(Long.parseLong(args[1]));
                System.out.println(args[2]);
                System.out.flush();
                Thread.sleep(Long.parseLong(args[3]));
            }
            case "line-repl" -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    switch (line) {
                        case "pid" ->
                            System.out.println(
                                    "response:pid:" + ProcessHandle.current().pid());
                        case "health" -> System.out.println("response:healthy");
                        case "reset" -> System.out.println("response:reset");
                        case "hold" -> {
                            Thread.sleep(500);
                            System.out.println("response:hold");
                        }
                        case "multi" -> {
                            System.out.println("first:multi");
                            System.out.println("second:multi");
                        }
                        case "slow" -> {
                            System.out.println("started:slow");
                            System.out.flush();
                            Thread.sleep(5000);
                        }
                        case "slow-response" -> {
                            Thread.sleep(5000);
                            System.out.println("response:slow");
                        }
                        case "many" -> {
                            for (int index = 0; index < 20; index++) {
                                System.out.println("noise-" + index + "-abcdefghijklmnop");
                            }
                            System.out.println("done");
                        }
                        case "stderr-burst" -> {
                            System.err.write("e".repeat(256 * 1024).getBytes(StandardCharsets.UTF_8));
                            System.err.println();
                            System.err.flush();
                            System.out.println("response:stderr-burst");
                        }
                        default -> System.out.println("response:" + line);
                    }
                    System.out.flush();
                }
            }
            case "two-line-delay-repl" -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("start:" + line);
                    System.out.flush();
                    Thread.sleep(100);
                    System.out.println("end:" + line);
                    System.out.flush();
                }
            }
            case "exit-after-read" -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                reader.readLine();
            }
            case "partial-stderr-sleep" -> {
                System.err.print("partial-error");
                System.err.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            case "mixed-partial-sleep" -> {
                System.out.print("partial-out");
                System.out.flush();
                System.err.print("partial-err");
                System.err.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            case "ignore-stdin" -> Thread.sleep(Long.parseLong(args[1]));
            case "prompt-repl" -> {
                System.out.print("ready> ");
                System.out.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("echo:" + line);
                    System.out.print("ready> ");
                    System.out.flush();
                }
            }
            case "ansi-prompt" -> {
                System.out.print("\u001B[31mREADY\u001B[0m> ");
                System.out.flush();
                Thread.sleep(5000);
            }
            case "exit-now" -> {}
            case "stdin-hex" -> {
                for (byte value : System.in.readAllBytes()) {
                    System.out.printf("%02x", value);
                }
                System.out.println();
            }
            case "stdout-bytes" -> System.out.write(new byte[] {0x00, (byte) 0xFF, 0x41});
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
            case "ignore-stdin-sleep" -> {
                System.out.print("started\n");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            case "flood-stderr" -> {
                System.err.write("e".repeat(Integer.parseInt(args[1])).getBytes(StandardCharsets.UTF_8));
                System.err.flush();
                System.out.print("done\n");
            }
            case "paired-streams" -> {
                System.out.println("out-start");
                System.out.flush();
                System.err.println("err-start");
                System.err.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            default -> throw new IllegalArgumentException("Unknown fixture command: " + args[0]);
        }
    }
}
