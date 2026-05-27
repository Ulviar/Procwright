package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.testcli.TestCli;
import java.nio.file.Path;

public final class TestCliSupport {

    private TestCliSupport() {}

    public static CommandSpec command() {
        return CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), TestCli.class.getName())
                .build();
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
