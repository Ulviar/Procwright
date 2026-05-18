package com.github.ulviar.icli.testcli;

import java.nio.file.Path;

/**
 * Entry point for the iCLI test CLI simulator.
 */
public final class TestCli {

    private TestCli() {}

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
