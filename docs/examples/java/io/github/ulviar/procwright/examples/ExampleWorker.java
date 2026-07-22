/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

final class ExampleWorker {

    private ExampleWorker() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "finite" -> finite();
            case "hang" -> hang();
            case "interactive" -> interactive();
            case "expect" -> expect();
            case "ansi-expect" -> ansiExpect();
            case "listen" -> listen();
            case "line" -> line();
            case "protocol" -> protocol();
            default -> throw new IllegalArgumentException("Unknown worker mode: " + args[0]);
        }
    }

    private static void finite() throws Exception {
        writeLine(writer(System.out), "procwright-ready");
    }

    private static void hang() throws InterruptedException {
        Thread.sleep(30_000);
    }

    private static void interactive() throws Exception {
        BufferedReader input = reader();
        String line = input.readLine();
        writeLine(writer(System.out), "answer:" + line);
        writeLine(writer(System.err), "processed");
    }

    private static void expect() throws Exception {
        BufferedReader input = reader();
        BufferedWriter output = writer(System.out);
        output.write("ready> ");
        output.flush();
        writeLine(output, "ok:" + input.readLine());
    }

    private static void ansiExpect() throws Exception {
        BufferedWriter output = writer(System.out);
        output.write("\u001B[31mready>\u001B[0m ");
        output.flush();
        Thread.sleep(30_000);
    }

    private static void listen() throws Exception {
        BufferedWriter output = writer(System.out);
        for (int index = 0; ; index++) {
            writeLine(output, "event:" + index);
            Thread.sleep(25);
        }
    }

    private static void line() throws Exception {
        BufferedReader input = reader();
        BufferedWriter output = writer(System.out);
        String line;
        while ((line = input.readLine()) != null) {
            writeLine(output, "response:" + line);
        }
    }

    private static void protocol() throws Exception {
        InputStream input = System.in;
        OutputStream output = System.out;
        String header;
        while ((header = readUtf8Line(input)) != null) {
            int length = Integer.parseInt(header);
            byte[] body = input.readNBytes(length);
            if (body.length != length) {
                throw new IllegalStateException("Unexpected end of request");
            }
            output.write(("len:" + length + "\n").getBytes(StandardCharsets.UTF_8));
            output.write(body);
            output.write("\nEND\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static BufferedReader reader() {
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    private static BufferedWriter writer(OutputStream output) {
        return new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    }

    private static void writeLine(BufferedWriter output, String line) throws IOException {
        output.write(line);
        output.write('\n');
        output.flush();
    }

    private static String readUtf8Line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                throw new IOException("Unexpected end of frame header");
            }
            if (value == '\n') {
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
            bytes.write(value);
        }
    }
}
