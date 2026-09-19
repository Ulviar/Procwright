/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.integration;

import io.github.ulviar.procwright.command.CommandSpec;

public final class WorkerServiceExample {

    private WorkerServiceExample() {}

    public static void main(String[] args) {
        CommandSpec command = JsonLinesTextWorker.command(args);
        // docs:start reuse
        try (var service = new TextWorkerService(command)) {
            System.out.println("hello: " + service.analyze("hello"));
            System.out.println("café: " + service.analyze("café"));
        }
        // docs:end reuse
    }
}
