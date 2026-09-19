/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.integration;

import io.github.ulviar.procwright.command.CommandSpec;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class WorkerPoolExample {

    private WorkerPoolExample() {}

    public static void main(String[] args) throws Exception {
        CommandSpec command = JsonLinesTextWorker.command(args);
        // docs:start pool
        try (var pool = TextWorkerService.draft(command).pooled().withMaxSize(2).open();
                var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = requests.submit(() -> pool.request(new TextWorkerService.Request("hello")));
            var second = requests.submit(() -> pool.request(new TextWorkerService.Request("café")));
            System.out.println("hello: " + first.get(15, TimeUnit.SECONDS));
            System.out.println("café: " + second.get(15, TimeUnit.SECONDS));
        }
        // docs:end pool
    }
}
