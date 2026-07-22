/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.command.CommandSpec;
import java.nio.file.Path;

final class ExampleSupport {

    private ExampleSupport() {}

    static CommandSpec workerCommand(String mode) {
        return CommandSpec.of(javaExecutable())
                .withArgs("-cp", System.getProperty("java.class.path"), ExampleWorker.class.getName(), mode);
    }

    static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
