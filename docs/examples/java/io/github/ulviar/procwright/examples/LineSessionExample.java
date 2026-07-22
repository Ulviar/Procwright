/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import java.time.Duration;

public final class LineSessionExample {

    private LineSessionExample() {}

    public static void main(String[] args) {
        try (LineSession session = Procwright.command(ExampleSupport.workerCommand("line"))
                .lineSession()
                .withRequestTimeout(Duration.ofSeconds(5))
                .open()) {
            LineResponse response = session.request("Zażółć gęślą jaźń");
            if (!response.text().equals("response:Zażółć gęślą jaźń")) {
                throw new IllegalStateException("Unexpected line response");
            }
        }
    }
}
