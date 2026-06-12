/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.testcli;

import java.nio.file.Path;

/**
 * Entry point for the Procwright test CLI simulator.
 */
public final class TestCli {

    private TestCli() {}

    /**
     * Runs the test CLI process.
     *
     * @param args command-line arguments
     * @throws Exception when a scenario fails outside its modeled exit-code path
     */
    public static void main(String[] args) throws Exception {
        int exitCode = TestCliApplication.run(
                args,
                System.in,
                System.out,
                System.err,
                System.getenv(),
                Path.of("").toAbsolutePath().normalize());
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
