/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build.release;

import java.nio.file.Path;

public final class ReleaseWorkflowCheck {
    private ReleaseWorkflowCheck() {}

    public static void main(String[] arguments) {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Expected paths to ci.yml, publish-maven-central.yml, and docs-deploy.yml");
        }
        new ReleaseWorkflowValidator().validate(Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]));
        System.out.println(
                "Release workflow YAML structure and static release wiring are valid; workflow execution remains the behavioral proof.");
    }
}
