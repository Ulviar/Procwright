/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.LineSession;
import java.util.ArrayList;
import java.util.List;

public final class MultilineResponseExample {

    private MultilineResponseExample() {}

    public static void main(String[] args) {
        // docs:start multiline
        try (LineSession session = Procwright.command(ExampleSupport.workerCommand("multiline"))
                .lineSession()
                .withMaxResponseLines(3)
                .withMaxResponseChars(1024)
                .withResponseDecoder(reader -> {
                    List<String> lines = new ArrayList<>();
                    String line;
                    while (!(line = reader.readLine()).equals("END")) {
                        lines.add(line);
                    }
                    return lines;
                })
                .open()) {
            for (String request : List.of("hello", "again")) {
                var response = session.request(request);
                if (!response.lines().equals(List.of("first:" + request, "second:" + request))) {
                    throw new IllegalStateException("Unexpected multiline response");
                }
            }
        }
        // docs:end multiline
    }
}
